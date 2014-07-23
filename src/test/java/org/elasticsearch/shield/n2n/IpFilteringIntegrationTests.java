/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.n2n;

import com.google.common.base.Charsets;
import com.google.common.net.InetAddresses;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.shield.plugin.SecurityPlugin;
import org.elasticsearch.shield.ssl.netty.NettySSLHttpServerTransportModule;
import org.elasticsearch.shield.ssl.netty.NettySSLTransportModule;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportModule;
import org.junit.Ignore;
import org.junit.Test;

import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.util.Locale;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 *
 */
@ElasticsearchIntegrationTest.ClusterScope(scope = ElasticsearchIntegrationTest.Scope.SUITE, numDataNodes = 1, transportClientRatio = 0.0, numClientNodes = 0)
public class IpFilteringIntegrationTests extends ElasticsearchIntegrationTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return settingsBuilder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("discovery.zen.ping.multicast.ping.enabled", false)
                .put("node.mode", "network")
                //.put("network.host", "127.0.0.1")
                .put("http.type", NettySSLHttpServerTransportModule.class.getName())
                .put(TransportModule.TRANSPORT_TYPE_KEY, NettySSLTransportModule.class.getName())
                .put("plugin.types", SecurityPlugin.class.getName())
                //.put("shield.n2n.file", configFile.getPath())
                .build();
    }

    @Test(expected = SocketException.class)
    public void testThatIpFilteringIsIntegratedIntoNettyPipelineViaHttp() throws Exception {
        TransportAddress transportAddress = internalCluster().getInstance(HttpServerTransport.class).boundAddress().boundAddress();
        assertThat(transportAddress, is(instanceOf(InetSocketTransportAddress.class)));
        InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) transportAddress;
        String url = String.format(Locale.ROOT, "http://%s:%s/", InetAddresses.toUriString(inetSocketTransportAddress.address().getAddress()), inetSocketTransportAddress.address().getPort());

        logger.info("Opening connection to {}", url);
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.connect();
        connection.getResponseCode();
    }

    @Ignore("Need to investigate further, why this does not fail")
    @Test(expected = SocketException.class)
    public void testThatIpFilteringIsIntegratedIntoNettyPipelineViaTransportClient() throws Exception {
        InetSocketTransportAddress transportAddress = (InetSocketTransportAddress) internalCluster().getDataNodeInstance(Transport.class).boundAddress().boundAddress();

        // TODO: This works and I do not understand why, telnet breaks...
        Socket socket = new Socket(transportAddress.address().getAddress(), transportAddress.address().getPort());
        socket.getOutputStream().write("foo".getBytes(Charsets.UTF_8));
        socket.getOutputStream().flush();
        socket.getInputStream().close();
        assertThat(socket.isConnected(), is(true));
        socket.close();
    }
}
