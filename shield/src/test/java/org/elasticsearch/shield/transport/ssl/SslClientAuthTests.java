/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.ssl;

import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.env.Environment;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.node.Node;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.ssl.ClientSSLService;
import org.elasticsearch.shield.transport.netty.ShieldNettyHttpServerTransport;
import org.elasticsearch.test.ShieldIntegTestCase;
import org.elasticsearch.test.ShieldSettingsSource;
import org.elasticsearch.test.rest.client.http.HttpRequestBuilder;
import org.elasticsearch.test.rest.client.http.HttpResponse;
import org.elasticsearch.transport.Transport;
import org.junit.Test;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.hamcrest.Matchers.containsString;

public class SslClientAuthTests extends ShieldIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return settingsBuilder()
                .put(super.nodeSettings(nodeOrdinal))
                // invert the require auth settings
                .put("shield.transport.ssl", true)
                .put(ShieldNettyHttpServerTransport.HTTP_SSL_SETTING, true)
                .put(ShieldNettyHttpServerTransport.HTTP_CLIENT_AUTH_SETTING, true)
                .put("transport.profiles.default.shield.ssl.client.auth", false)
                .put(Node.HTTP_ENABLED, true)
                .build();
    }

    @Override
    protected boolean sslTransportEnabled() {
        return true;
    }

    @Test(expected = SSLHandshakeException.class)
    public void testThatHttpFailsWithoutSslClientAuth() throws IOException {
        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                SSLContexts.createDefault(),
                SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        CloseableHttpClient client = HttpClients.custom().setSSLSocketFactory(socketFactory).build();

        new HttpRequestBuilder(client)
                .httpTransport(internalTestCluster().getInstance(HttpServerTransport.class))
                .method("GET").path("/")
                .protocol("https")
                .execute();
    }

    @Test
    public void testThatHttpWorksWithSslClientAuth() throws IOException {
        Settings settings = settingsBuilder().put(ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient.jks", "testclient")).build();
        Environment env = new Environment(settingsBuilder().put("path.home", createTempDir()).build());
        ClientSSLService sslService = new ClientSSLService(settings, env);

        SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(
                sslService.sslContext(),
                SSLConnectionSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        CloseableHttpClient client = HttpClients.custom().setSSLSocketFactory(socketFactory).build();

        HttpResponse response = new HttpRequestBuilder(client)
                .httpTransport(internalTestCluster().getInstance(HttpServerTransport.class))
                .method("GET").path("/")
                .protocol("https")
                .addHeader("Authorization", basicAuthHeaderValue(transportClientUsername(), transportClientPassword()))
                .execute();
        assertThat(response.getBody(), containsString("You Know, for Search"));
    }

    @Test
    public void testThatTransportWorksWithoutSslClientAuth() throws Exception {
        // specify an arbitrary keystore, that does not include the certs needed to connect to the transport protocol
        Path store = getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient-client-profile.jks");

        if (Files.notExists(store)) {
            throw new ElasticsearchException("store path doesn't exist");
        }

        Settings settings = settingsBuilder()
                .put("path.home", createTempDir())
                .put("shield.transport.ssl", true)
                .put("shield.ssl.keystore.path", store)
                .put("shield.ssl.keystore.password", "testclient-client-profile")
                .put("cluster.name", internalTestCluster().getClusterName())
                .put("shield.user", transportClientUsername() + ":" + new String(transportClientPassword().internalChars()))
                .build();
        try (TransportClient client = TransportClient.builder().settings(settings).addPlugin(ShieldPlugin.class).addPlugin(licensePluginClass()).build()) {
            Transport transport = internalTestCluster().getDataNodeInstance(Transport.class);
            TransportAddress transportAddress = transport.boundAddress().publishAddress();
            client.addTransportAddress(transportAddress);

            assertGreenClusterState(client);
        }
    }
}
