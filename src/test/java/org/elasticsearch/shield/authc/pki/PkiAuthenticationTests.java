/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.pki;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.node.internal.InternalNode;
import org.elasticsearch.shield.transport.netty.ShieldNettyHttpServerTransport;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.ShieldIntegrationTest;
import org.elasticsearch.test.ShieldSettingsSource;
import org.elasticsearch.transport.Transport;
import org.junit.Test;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Locale;

import static org.hamcrest.Matchers.*;

/**
 * Test authentication via PKI on both REST and Transport layers
 */
@ClusterScope(numClientNodes = 0, numDataNodes = 1)
public class PkiAuthenticationTests extends ShieldIntegrationTest {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(InternalNode.HTTP_ENABLED, true)
                .put(ShieldNettyHttpServerTransport.HTTP_SSL_SETTING, true)
                .put(ShieldNettyHttpServerTransport.HTTP_CLIENT_AUTH_SETTING, true)
                .put("shield.authc.realms.esusers.type", "esusers")
                .put("shield.authc.realms.esusers.order", "0")
                .put("shield.authc.realms.pki1.type", "pki")
                .put("shield.authc.realms.pki1.order", "1")
                .put("shield.authc.realms.pki1.truststore.path", getResource("/org/elasticsearch/shield/transport/ssl/certs/simple/truststore-testnode-only.jks"))
                .put("shield.authc.realms.pki1.truststore.password", "truststore-testnode-only")
                .put("shield.authc.realms.pki1.files.role_mapping", getResource("role_mapping.yml"))
                .build();
    }

    @Override
    protected boolean sslTransportEnabled() {
        return true;
    }

    @Test
    public void testTransportClientCanAuthenticateViaPki() {
        Settings settings = ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks", "testnode");
        try (TransportClient client = createTransportClient(settings)) {
            client.addTransportAddress(internalCluster().getInstance(Transport.class).boundAddress().boundAddress());
            IndexResponse response = client.prepareIndex("foo", "bar").setSource("pki", "auth").get();
            assertThat(response.isCreated(), is(true));
        }
    }

    /**
     * Test uses the testclient cert which is trusted by the SSL layer BUT it is not trusted by the PKI authentication
     * realm
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testTransportClientAuthenticationFailure() {
        try (TransportClient client = createTransportClient(ImmutableSettings.EMPTY)) {
            client.addTransportAddress(internalCluster().getInstance(Transport.class).boundAddress().boundAddress());
            client.prepareIndex("foo", "bar").setSource("pki", "auth").get();
            fail("transport client should not have been able to authenticate");
        }
    }

    @Test
    public void testRestAuthenticationViaPki() throws Exception {
        SSLContext context = getRestSSLContext("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks", "testnode");
        try (CloseableHttpClient client = HttpClients.custom().setSslcontext(context).build()) {
            HttpPut put = new HttpPut(getNodeUrl() + "foo");
            try (CloseableHttpResponse response = client.execute(put)) {
                String body = EntityUtils.toString(response.getEntity());
                assertThat(body, containsString("\"acknowledged\":true"));
            }
        }
    }

    @Test
    public void testRestAuthenticationFailure() throws Exception {
        SSLContext context = getRestSSLContext("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient.jks", "testclient");
        try (CloseableHttpClient client = HttpClients.custom().setSslcontext(context).build()) {
            HttpPut put = new HttpPut(getNodeUrl() + "foo");
            try (CloseableHttpResponse response = client.execute(put)) {
                assertThat(response.getStatusLine().getStatusCode(), is(401));
                String body = EntityUtils.toString(response.getEntity());
                assertThat(body, containsString("AuthenticationException[unable to authenticate user [Elasticsearch Test Client]"));
            }
        }
    }

    private static SSLContext getRestSSLContext(String keystoreResourcePath, String password) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        Path store = Paths.get(PkiAuthenticationTests.class.getResource(keystoreResourcePath).toURI());
        KeyStore ks;
        try (InputStream in = Files.newInputStream(store)) {
            ks = KeyStore.getInstance("jks");
            ks.load(in, password.toCharArray());
        }

        kmf.init(ks, password.toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        context.init(kmf.getKeyManagers(), tmf.getTrustManagers(), new SecureRandom());

        return context;
    }

    private TransportClient createTransportClient(Settings additionalSettings) {
        ImmutableSettings.Builder builder = ImmutableSettings.builder()
                .put(transportClientSettings())
                .put(additionalSettings)
                .put("cluster.name", internalCluster().getClusterName());
        builder.remove("shield.user");
        builder.remove("request.headers.Authorization");
        return new TransportClient(builder.build());
    }

    private String getNodeUrl() {
        TransportAddress transportAddress = internalCluster().getInstance(HttpServerTransport.class).boundAddress().boundAddress();
        assertThat(transportAddress, is(instanceOf(InetSocketTransportAddress.class)));
        InetSocketTransportAddress inetSocketTransportAddress = (InetSocketTransportAddress) transportAddress;
        return String.format(Locale.ROOT, "https://localhost:%s/", inetSocketTransportAddress.address().getPort());
    }
}
