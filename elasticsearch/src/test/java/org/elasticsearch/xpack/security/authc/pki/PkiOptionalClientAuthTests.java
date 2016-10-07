/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.pki;

import org.apache.http.message.BasicHeader;
import org.apache.http.nio.conn.ssl.SSLIOSessionStrategy;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.test.SecurityIntegTestCase;
import org.elasticsearch.test.SecuritySettingsSource;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.xpack.TestXPackTransportClient;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.ssl.SSLClientAuth;
import org.junit.BeforeClass;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;

import static org.elasticsearch.test.SecuritySettingsSource.DEFAULT_PASSWORD;
import static org.elasticsearch.test.SecuritySettingsSource.DEFAULT_USER_NAME;
import static org.elasticsearch.test.SecuritySettingsSource.getSSLSettingsForStore;
import static org.hamcrest.Matchers.is;

public class PkiOptionalClientAuthTests extends SecurityIntegTestCase {

    private static int randomClientPort;

    @BeforeClass
    public static void initPort() {
        randomClientPort = randomIntBetween(49000, 65500);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        String randomClientPortRange = randomClientPort + "-" + (randomClientPort+100);

        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(NetworkModule.HTTP_ENABLED.getKey(), true)
                .put("xpack.security.http.ssl.enabled", true)
                .put("xpack.security.http.ssl.client_authentication", SSLClientAuth.OPTIONAL)
                .put("xpack.security.authc.realms.file.type", "file")
                .put("xpack.security.authc.realms.file.order", "0")
                .put("xpack.security.authc.realms.pki1.type", "pki")
                .put("xpack.security.authc.realms.pki1.order", "1")
                .put("xpack.security.authc.realms.pki1.truststore.path",
                        getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/truststore-testnode-only.jks"))
                .put("xpack.security.authc.realms.pki1.truststore.password", "truststore-testnode-only")
                .put("xpack.security.authc.realms.pki1.files.role_mapping", getDataPath("role_mapping.yml"))
                .put("transport.profiles.want_client_auth.port", randomClientPortRange)
                .put("transport.profiles.want_client_auth.bind_host", "localhost")
                .put("transport.profiles.want_client_auth.xpack.security.ssl.client_authentication", SSLClientAuth.OPTIONAL)
                .build();
    }

    @Override
    protected boolean sslTransportEnabled() {
        return true;
    }

    public void testRestClientWithoutClientCertificate() throws Exception {
        SSLIOSessionStrategy sessionStrategy = new SSLIOSessionStrategy(getSSLContext());
        try (RestClient restClient = createRestClient(httpClientBuilder -> httpClientBuilder.setSSLStrategy(sessionStrategy), "https")) {
            try {
                restClient.performRequest("GET", "_nodes");
                fail("request should have failed");
            } catch(ResponseException e) {
                assertThat(e.getResponse().getStatusLine().getStatusCode(), is(401));
            }

            Response response = restClient.performRequest("GET", "_nodes",
                    new BasicHeader(UsernamePasswordToken.BASIC_AUTH_HEADER,
                            UsernamePasswordToken.basicAuthHeaderValue(SecuritySettingsSource.DEFAULT_USER_NAME,
                                    new SecuredString(SecuritySettingsSource.DEFAULT_PASSWORD.toCharArray()))));
            assertThat(response.getStatusLine().getStatusCode(), is(200));
        }
    }

    public void testTransportClientWithoutClientCertificate() {
        Transport transport = internalCluster().getDataNodeInstance(Transport.class);
        int port = randomFrom(transport.profileBoundAddresses().get("want_client_auth").boundAddresses()).address().getPort();

        Settings sslSettingsForStore = getSSLSettingsForStore
                ("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/truststore-testnode-only.jks", "truststore-testnode-only");
        Settings settings = Settings.builder()
                .put(sslSettingsForStore)
                .put(Security.USER_SETTING.getKey(), DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("xpack.ssl.client_authentication", SSLClientAuth.REQUIRED)
                .build();


        try (TransportClient client = new TestXPackTransportClient(settings)) {
            client.addTransportAddress(new TransportAddress(InetAddress.getLoopbackAddress(), port));
            assertGreenClusterState(client);
        }
    }

    private SSLContext getSSLContext() throws Exception {
        SSLContext sc = SSLContext.getInstance("TLSv1.2");
        Path truststore = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/truststore-testnode-only.jks");
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream stream = Files.newInputStream(truststore)) {
            keyStore.load(stream, "truststore-testnode-only".toCharArray());
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        sc.init(null, factory.getTrustManagers(), new SecureRandom());
        return sc;
    }
}
