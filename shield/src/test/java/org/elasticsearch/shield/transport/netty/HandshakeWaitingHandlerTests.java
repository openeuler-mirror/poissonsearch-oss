/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.netty;

import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.env.Environment;
import org.elasticsearch.shield.ShieldSettingsFilter;
import org.elasticsearch.shield.ssl.ServerSSLService;
import org.elasticsearch.test.ESTestCase;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.ssl.SslHandler;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.hamcrest.Matchers.*;

public class HandshakeWaitingHandlerTests extends ESTestCase {

    private static final int CONCURRENT_CLIENT_REQUESTS = 20;

    private int iterations;
    private int randomPort;

    private ServerBootstrap serverBootstrap;
    private ClientBootstrap clientBootstrap;
    private SSLContext sslContext;

    private final AtomicBoolean failed = new AtomicBoolean(false);
    private volatile Throwable failureCause = null;
    private ExecutorService threadPoolExecutor;

    @Before
    public void setup() throws Exception {
        randomPort = randomIntBetween(49000, 65500);
        iterations = randomIntBetween(10, 100);

        Settings settings = settingsBuilder()
                .put("shield.ssl.keystore.path", getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks"))
                .put("shield.ssl.keystore.password", "testnode")
                .build();
        Environment env = new Environment(settingsBuilder().put("path.home", createTempDir()).build());
        ShieldSettingsFilter settingsFilter = new ShieldSettingsFilter(settings, new SettingsFilter(settings));
        ServerSSLService sslService = new ServerSSLService(settings, settingsFilter, env);

        sslContext = sslService.sslContext();

        startBootstrap();

        clientBootstrap = new ClientBootstrap(new NioClientSocketChannelFactory());

        threadPoolExecutor = Executors.newFixedThreadPool(CONCURRENT_CLIENT_REQUESTS);
    }

    @After
    public void reset() throws InterruptedException {
        terminate(threadPoolExecutor);

        clientBootstrap.shutdown();
        clientBootstrap.releaseExternalResources();

        serverBootstrap.shutdown();
        serverBootstrap.releaseExternalResources();

        failed.set(false);
        failureCause = null;
    }

    @Test
    public void testWriteBeforeHandshakeFailsWithoutHandler() throws Exception {
        clientBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            @Override
            public ChannelPipeline getPipeline() throws Exception {
                final SSLEngine engine = sslContext.createSSLEngine();
                engine.setUseClientMode(true);
                return Channels.pipeline(
                        new SslHandler(engine));
            }
        });

        List<Callable<ChannelFuture>> callables = new ArrayList<>(CONCURRENT_CLIENT_REQUESTS);
        for (int i = 0; i < CONCURRENT_CLIENT_REQUESTS; i++) {
            callables.add(new WriteBeforeHandshakeCompletedCallable(clientBootstrap, randomPort));
        }

        for (int i = 0; i < iterations; i++) {
            List<Future<ChannelFuture>> futures = threadPoolExecutor.invokeAll(callables);
            for (Future<ChannelFuture> future : futures) {
                ChannelFuture handshakeFuture = future.get();
                handshakeFuture.await();
                handshakeFuture.getChannel().close();
            }

            if (failed.get()) {
                assertThat(failureCause, anyOf(instanceOf(SSLException.class), instanceOf(AssertionError.class)));
                break;
            }
        }

        assertThat("Expected this test to fail with an SSLException or AssertionError", failed.get(), is(true));
    }

    @Test
    @AwaitsFix(bugUrl = "https://github.com/elasticsearch/elasticsearch-shield/issues/533")
    public void testWriteBeforeHandshakePassesWithHandshakeWaitingHandler() throws Exception {
        clientBootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            @Override
            public ChannelPipeline getPipeline() throws Exception {
                final SSLEngine engine = sslContext.createSSLEngine();
                engine.setUseClientMode(true);
                return Channels.pipeline(
                        new SslHandler(engine),
                        new HandshakeWaitingHandler(Loggers.getLogger(HandshakeWaitingHandler.class)));
            }
        });

        List<Callable<ChannelFuture>> callables = new ArrayList<>(CONCURRENT_CLIENT_REQUESTS);
        for (int i = 0; i < CONCURRENT_CLIENT_REQUESTS; i++) {
            callables.add(new WriteBeforeHandshakeCompletedCallable(clientBootstrap, randomPort));
        }

        for (int i = 0; i < iterations; i++) {
            List<Future<ChannelFuture>> futures = threadPoolExecutor.invokeAll(callables);
            for (Future<ChannelFuture> future : futures) {
                ChannelFuture handshakeFuture = future.get();
                handshakeFuture.await();

                // Wait for pending writes to prevent IOExceptions
                Channel channel = handshakeFuture.getChannel();
                HandshakeWaitingHandler handler = channel.getPipeline().get(HandshakeWaitingHandler.class);
                while (handler != null && handler.hasPendingWrites()) {
                    Thread.sleep(10);
                }

                channel.close();
            }

            assertNotFailed();
        }
    }

    private void assertNotFailed() {
        if (failed.get()) {
            StringWriter writer = new StringWriter();
            if (failed.get()) {
                failureCause.printStackTrace(new PrintWriter(writer));
            }

            assertThat("Expected this test to always pass with the HandshakeWaitingHandler in pipeline\n" + writer.toString(), failed.get(), is(false));
        }
    }

    private void startBootstrap() {
        serverBootstrap = new ServerBootstrap(new NioServerSocketChannelFactory());
        serverBootstrap.setPipelineFactory(getServerFactory());
        int tries = 1;
        int maxTries = 10;
        while (tries <= maxTries) {
            try {
                serverBootstrap.bind(new InetSocketAddress("localhost", randomPort));
                break;
            } catch (Throwable t) {
                if (t.getCause() instanceof BindException) {
                    logger.error("Tried to bind to port [{}], going to retry", randomPort);
                    randomPort = randomIntBetween(49000, 65500);
                }
                if (tries >= maxTries) {
                    throw new RuntimeException("Failed to start server bootstrap [" + tries + "] times, stopping", t);
                }
                tries++;
            }
        }
    }

    private ChannelPipelineFactory getServerFactory() {
        return new ChannelPipelineFactory() {
            public ChannelPipeline getPipeline() throws Exception {
                final SSLEngine sslEngine = sslContext.createSSLEngine();
                sslEngine.setUseClientMode(false);
                return Channels.pipeline(new SslHandler(sslEngine),
                    new SimpleChannelHandler() {

                        @Override
                        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
                            // Sink the message
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
                            Throwable cause = e.getCause();
                            // Only save first cause
                            if (failed.compareAndSet(false, true)) {
                                failureCause = cause;
                            }
                            ctx.getChannel().close();
                        }
                    });
            }
        };
    }

    private static class WriteBeforeHandshakeCompletedCallable implements Callable<ChannelFuture> {

        private final ClientBootstrap bootstrap;
        private final int port;

        WriteBeforeHandshakeCompletedCallable(ClientBootstrap bootstrap, int port) {
            this.bootstrap = bootstrap;
            this.port = port;
        }

        @Override
        public ChannelFuture call() throws Exception {
            ChannelBuffer buffer = ChannelBuffers.buffer(8);
            buffer.writeLong(SecureRandom.getInstance("SHA1PRNG").nextLong());

            // Connect and wait, then immediately start writing
            ChannelFuture future = bootstrap.connect(new InetSocketAddress("localhost", port));
            future.awaitUninterruptibly();
            Channel channel = future.getChannel();

            // Do not call handshake before writing as it will most likely succeed before a write begins
            // in the test
            ChannelFuture handshakeFuture = null;
            for (int i = 0; i < 100; i++) {
                if (handshakeFuture == null) {
                    handshakeFuture = channel.getPipeline().get(SslHandler.class).handshake();
                }
                channel.write(buffer);
            }

            return handshakeFuture;
        }
    }
}
