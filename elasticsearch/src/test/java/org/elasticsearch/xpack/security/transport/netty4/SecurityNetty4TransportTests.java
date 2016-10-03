/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.transport.netty4;

import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslHandler;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.env.Environment;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.netty4.Netty4MockUtil;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.ssl.SSLClientAuth;
import org.elasticsearch.xpack.ssl.SSLService;
import org.junit.Before;

import javax.net.ssl.SSLEngine;
import java.nio.file.Path;
import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;

public class SecurityNetty4TransportTests extends ESTestCase {

    private Environment env;
    private SSLService sslService;

    @Before
    public void createSSLService() throws Exception {
        Path testnodeStore = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks");
        Settings settings = Settings.builder()
                .put("xpack.ssl.keystore.path", testnodeStore)
                .put("xpack.ssl.keystore.password", "testnode")
                .put("path.home", createTempDir())
                .build();
        env = new Environment(settings);
        sslService = new SSLService(settings, env);
    }

    private SecurityNetty4Transport createTransport(boolean sslEnabled) {
        return createTransport(sslEnabled, Settings.EMPTY);
    }

    private SecurityNetty4Transport createTransport(boolean sslEnabled, Settings additionalSettings) {
        final Settings settings =
                Settings.builder()
                        .put(XPackSettings.TRANSPORT_SSL_ENABLED.getKey(), sslEnabled)
                        .put(additionalSettings)
                        .build();
        return new SecurityNetty4Transport(
                settings,
                mock(ThreadPool.class),
                mock(NetworkService.class),
                mock(BigArrays.class),
                mock(NamedWriteableRegistry.class),
                mock(CircuitBreakerService.class),
                null,
                sslService);
    }

    public void testThatSSLCanBeDisabledByProfile() throws Exception {
        SecurityNetty4Transport transport = createTransport(true);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client",
                Settings.builder().put("xpack.security.ssl.enabled", false).build());
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class), nullValue());
    }

    public void testThatSSLCanBeEnabledByProfile() throws Exception {
        SecurityNetty4Transport transport = createTransport(false);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client",
                Settings.builder().put("xpack.security.ssl.enabled", true).build());
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class), notNullValue());
    }

    public void testThatProfileTakesDefaultSSLSetting() throws Exception {
        SecurityNetty4Transport transport = createTransport(true);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client", Settings.EMPTY);
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine(), notNullValue());
    }

    public void testDefaultClientAuth() throws Exception {
        SecurityNetty4Transport transport = createTransport(true);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client", Settings.EMPTY);
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine().getNeedClientAuth(), is(true));
        assertThat(ch.pipeline().get(SslHandler.class).engine().getWantClientAuth(), is(false));
    }

    public void testRequiredClientAuth() throws Exception {
        String value = randomFrom(SSLClientAuth.REQUIRED.name(), SSLClientAuth.REQUIRED.name().toLowerCase(Locale.ROOT));
        Settings settings = Settings.builder()
                .put(env.settings())
                .put("xpack.ssl.client_authentication", value)
                .build();
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = createTransport(true, settings);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client", Settings.EMPTY);
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine().getNeedClientAuth(), is(true));
        assertThat(ch.pipeline().get(SslHandler.class).engine().getWantClientAuth(), is(false));
    }

    public void testNoClientAuth() throws Exception {
        String value = randomFrom(SSLClientAuth.NONE.name(), SSLClientAuth.NONE.name().toLowerCase(Locale.ROOT));
        Settings settings = Settings.builder()
                .put(env.settings())
                .put("xpack.ssl.client_authentication", value)
                .build();
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = createTransport(true, settings);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client", Settings.EMPTY);
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine().getNeedClientAuth(), is(false));
        assertThat(ch.pipeline().get(SslHandler.class).engine().getWantClientAuth(), is(false));
    }

    public void testOptionalClientAuth() throws Exception {
        String value = randomFrom(SSLClientAuth.OPTIONAL.name(), SSLClientAuth.OPTIONAL.name().toLowerCase(Locale.ROOT));
        Settings settings = Settings.builder()
                .put(env.settings())
                .put("xpack.ssl.client_authentication", value)
                .build();
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = createTransport(true, settings);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client", Settings.EMPTY);
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine().getNeedClientAuth(), is(false));
        assertThat(ch.pipeline().get(SslHandler.class).engine().getWantClientAuth(), is(true));
    }

    public void testProfileRequiredClientAuth() throws Exception {
        String value = randomFrom(SSLClientAuth.REQUIRED.name(), SSLClientAuth.REQUIRED.name().toLowerCase(Locale.ROOT));
        Settings settings = Settings.builder()
                .put(env.settings())
                .put("xpack.security.transport.ssl.enabled", true)
                .put("transport.profiles.client.xpack.security.ssl.client_authentication", value)
                .build();
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = createTransport(true, settings);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client",
                Settings.builder().put("xpack.security.ssl.client_authentication", value).build());
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine().getNeedClientAuth(), is(true));
        assertThat(ch.pipeline().get(SslHandler.class).engine().getWantClientAuth(), is(false));
    }

    public void testProfileNoClientAuth() throws Exception {
        String value = randomFrom(SSLClientAuth.NONE.name(), SSLClientAuth.NONE.name().toLowerCase(Locale.ROOT));
        Settings settings = Settings.builder()
                .put(env.settings())
                .put("xpack.security.transport.ssl.enabled", true)
                .put("transport.profiles.client.xpack.security.ssl.client_authentication", value)
                .build();
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = createTransport(true, settings);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        ChannelHandler handler = transport.getServerChannelInitializer("client",
                Settings.builder().put("xpack.security.ssl.client_authentication", value).build());
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine().getNeedClientAuth(), is(false));
        assertThat(ch.pipeline().get(SslHandler.class).engine().getWantClientAuth(), is(false));
    }

    public void testProfileOptionalClientAuth() throws Exception {
        String value = randomFrom(SSLClientAuth.OPTIONAL.name(), SSLClientAuth.OPTIONAL.name().toLowerCase(Locale.ROOT));
        Settings settings = Settings.builder()
                .put(env.settings())
                .put("xpack.security.transport.ssl.enabled", true)
                .put("transport.profiles.client.xpack.security.ssl.client_authentication", value)
                .build();
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = createTransport(true, settings);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        final ChannelHandler handler = transport.getServerChannelInitializer("client",
                Settings.builder().put("xpack.security.ssl.client_authentication", value).build());
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        assertThat(ch.pipeline().get(SslHandler.class).engine().getNeedClientAuth(), is(false));
        assertThat(ch.pipeline().get(SslHandler.class).engine().getWantClientAuth(), is(true));
    }

    public void testThatExceptionIsThrownWhenConfiguredWithoutSslKey() throws Exception {
        Settings settings = Settings.builder()
                .put("xpack.ssl.truststore.path",
                        getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks"))
                .put("xpack.ssl.truststore.password", "testnode")
                .put(XPackSettings.TRANSPORT_SSL_ENABLED.getKey(), true)
                .put("path.home", createTempDir())
                .build();
        env = new Environment(settings);
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = new SecurityNetty4Transport(settings, mock(ThreadPool.class), mock(NetworkService.class),
                mock(BigArrays.class), mock(NamedWriteableRegistry.class), mock(CircuitBreakerService.class), null, sslService);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> transport.getServerChannelInitializer(randomAsciiOfLength(6), Settings.EMPTY));
        assertThat(e.getMessage(), containsString("key must be provided"));
    }

    public void testNoExceptionWhenConfiguredWithoutSslKeySSLDisabled() throws Exception {
        Settings settings = Settings.builder()
                .put("xpack.ssl.truststore.path",
                        getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks"))
                .put("xpack.ssl.truststore.password", "testnode")
                .put(XPackSettings.TRANSPORT_SSL_ENABLED.getKey(), false)
                .put("path.home", createTempDir())
                .build();
        env = new Environment(settings);
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = new SecurityNetty4Transport(settings, mock(ThreadPool.class), mock(NetworkService.class),
                mock(BigArrays.class), mock(NamedWriteableRegistry.class), mock(CircuitBreakerService.class), null, sslService);
        assertNotNull(transport.getServerChannelInitializer(randomAsciiOfLength(6), Settings.EMPTY));
    }

    public void testTransportSSLOverridesGlobalSSL() throws Exception {
        final boolean useGlobalKeystoreWithoutKey = randomBoolean();
        Settings.Builder builder = Settings.builder()
                .put("xpack.security.transport.ssl.keystore.path",
                        getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks"))
                .put("xpack.security.transport.ssl.keystore.password", "testnode")
                .put("xpack.security.transport.ssl.client_authentication", "none")
                .put(XPackSettings.TRANSPORT_SSL_ENABLED.getKey(), true)
                .put("path.home", createTempDir());
        if (useGlobalKeystoreWithoutKey) {
            builder.put("xpack.ssl.keystore.path",
                    getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/truststore-testnode-only.jks"))
                    .put("xpack.ssl.keystore.password", "truststore-testnode-only");
        }
        Settings settings = builder.build();
        env = new Environment(settings);
        sslService = new SSLService(settings, env);
        SecurityNetty4Transport transport = createTransport(true, settings);
        Netty4MockUtil.setOpenChannelsHandlerToMock(transport);
        final ChannelHandler handler = transport.getServerChannelInitializer("default", Settings.EMPTY);
        final EmbeddedChannel ch = new EmbeddedChannel(handler);
        final SSLEngine engine = ch.pipeline().get(SslHandler.class).engine();
        assertFalse(engine.getNeedClientAuth());
        assertFalse(engine.getWantClientAuth());

        // get the global and verify that it is different in that it requires client auth
        final SSLEngine globalEngine = sslService.createSSLEngine(Settings.EMPTY, Settings.EMPTY);
        assertTrue(globalEngine.getNeedClientAuth());
        assertFalse(globalEngine.getWantClientAuth());
    }
}
