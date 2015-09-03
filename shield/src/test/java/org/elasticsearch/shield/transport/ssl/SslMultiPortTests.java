/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.ssl;

import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.test.ShieldIntegTestCase;
import org.elasticsearch.test.ShieldSettingsSource;
import org.elasticsearch.transport.Transport;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.test.ShieldSettingsSource.DEFAULT_PASSWORD;
import static org.elasticsearch.test.ShieldSettingsSource.DEFAULT_USER_NAME;
import static org.hamcrest.CoreMatchers.is;

public class SslMultiPortTests extends ShieldIntegTestCase {

    private static int randomClientPort;
    private static int randomNonSslPort;
    private static int randomNoClientAuthPort;

    @BeforeClass
    public static void getRandomPort() {
        randomClientPort = randomIntBetween(49000, 65500); // ephemeral port
        randomNonSslPort = randomIntBetween(49000, 65500);
        randomNoClientAuthPort = randomIntBetween(49000, 65500);
    }

    /**
     * On each node sets up the following profiles:
     * <ul>
     *     <li>default: testnode keystore. Requires client auth</li>
     *     <li>client: testnode-client-profile keystore that only trusts the testclient cert. Requires client auth</li>
     *     <li>no_client_auth: testnode keystore. Does not require client auth</li>
     *     <li>no_ssl: plaintext transport profile</li>
     * </ul>
     */
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        String randomClientPortRange = randomClientPort + "-" + (randomClientPort+100);
        String randomNonSslPortRange = randomNonSslPort + "-" + (randomNonSslPort+100);
        String randomNoClientAuthPortRange = randomNoClientAuthPort + "-" + (randomNoClientAuthPort+100);

        Path store;
        try {
            store = getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode-client-profile.jks");
            assertThat(Files.exists(store), is(true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return settingsBuilder()
                .put(super.nodeSettings(nodeOrdinal))
                // client set up here
                .put("transport.profiles.client.port", randomClientPortRange)
                .put("transport.profiles.client.bind_host", "localhost") // make sure this is "localhost", no matter if ipv4 or ipv6, but be consistent
                .put("transport.profiles.client.shield.truststore.path", store.toAbsolutePath()) // settings for client truststore
                .put("transport.profiles.client.shield.truststore.password", "testnode-client-profile")
                .put("transport.profiles.no_ssl.port", randomNonSslPortRange)
                .put("transport.profiles.no_ssl.bind_host", "localhost")
                .put("transport.profiles.no_ssl.shield.ssl", "false")
                .put("transport.profiles.no_client_auth.port", randomNoClientAuthPortRange)
                .put("transport.profiles.no_client_auth.bind_host", "localhost")
                .put("transport.profiles.no_client_auth.shield.ssl.client.auth", false)
                .build();
    }

    @Override
    protected boolean sslTransportEnabled() {
        return true;
    }

    private TransportClient createTransportClient(Settings additionalSettings) {
        Settings settings = settingsBuilder().put(transportClientSettings())
                .put("name", "programmatic_transport_client")
                .put("cluster.name", internalCluster().getClusterName())
                .put("path.home", createTempDir())
                .put(additionalSettings)
                .build();
        return TransportClient.builder().settings(settings).loadConfigSettings(false)
                .addPlugin(licensePluginClass())
                .addPlugin(ShieldPlugin.class)
                .build();
    }

    /**
     * Uses the internal cluster's transport client to test connection to the default profile. The internal transport
     * client uses the same SSL settings as the default profile so a connection should always succeed
     */
    @Test
    public void testThatStandardTransportClientCanConnectToDefaultProfile() throws Exception {
        assertGreenClusterState(internalCluster().transportClient());
    }

    /**
     * Uses a transport client with the same settings as the internal cluster transport client to test connection to the
     * no_client_auth profile. The internal transport client is not used here since we are connecting to a different
     * profile. Since the no_client_auth profile does not require client authentication, the standard transport client
     * connection should always succeed as the settings are the same as the default profile except for the port and
     * disabling the client auth requirement
     */
    @Test
    public void testThatStandardTransportClientCanConnectToNoClientAuthProfile() throws Exception {
        try(TransportClient transportClient = createTransportClient(Settings.EMPTY)) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_client_auth")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with the same settings as the internal cluster transport client to test connection to the
     * client profile. The internal transport client is not used here since we are connecting to a different
     * profile. The client profile requires client auth and only trusts the certificate in the testclient-client-profile
     * keystore so this connection will fail as the certificate presented by the standard transport client is not trusted
     * by this profile
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatStandardTransportClientCannotConnectToClientProfile() throws Exception {
        try(TransportClient transportClient = createTransportClient(Settings.EMPTY)) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("client")));
            transportClient.admin().cluster().prepareHealth().get();
        }
    }

    /**
     * Uses a transport client with the same settings as the internal cluster transport client to test connection to the
     * no_ssl profile. The internal transport client is not used here since we are connecting to a different
     * profile. The no_ssl profile is plain text and the standard transport client uses SSL, so a connection will never work
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatStandardTransportClientCannotConnectToNoSslProfile() throws Exception {
        try (TransportClient transportClient = createTransportClient(Settings.EMPTY)) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_ssl")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with a custom keystore; this keystore testclient-client-profile.jks trusts the testnode
     * certificate and had its own self signed certificate. This test connects to the client profile, which is only
     * set to trust the testclient-client-profile certificate so the connection should always succeed
     */
    @Test
    public void testThatProfileTransportClientCanConnectToClientProfile() throws Exception {
        Settings settings = ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient-client-profile.jks", "testclient-client-profile");
        try (TransportClient transportClient = createTransportClient(settings)) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("client")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with a custom keystore; this keystore testclient-client-profile.jks trusts the testnode
     * certificate and had its own self signed certificate. This test connects to the no_client_auth profile, which
     * uses a truststore that does not trust the testclient-client-profile certificate but does not require client
     * authentication
     */
    @Test
    public void testThatProfileTransportClientCanConnectToNoClientAuthProfile() throws Exception {
        Settings settings = ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient-client-profile.jks", "testclient-client-profile");
        try (TransportClient transportClient = createTransportClient(settings)) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_client_auth")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with a custom keystore; this keystore testclient-client-profile.jks trusts the testnode
     * certificate and had its own self signed certificate. This test connects to the default profile, which
     * uses a truststore that does not trust the testclient-client-profile certificate and requires client authentication
     * so the connection should always fail
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatProfileTransportClientCannotConnectToDefaultProfile() throws Exception {
        Settings settings = ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient-client-profile.jks", "testclient-client-profile");
        try (TransportClient transportClient = createTransportClient(settings)) {
            TransportAddress transportAddress = internalCluster().getInstance(Transport.class).boundAddress().boundAddress();
            transportClient.addTransportAddress(transportAddress);
            transportClient.admin().cluster().prepareHealth().get();
        }
    }

    /**
     * Uses a transport client with a custom keystore; this keystore testclient-client-profile.jks trusts the testnode
     * certificate and had its own self signed certificate. This test connects to the no_ssl profile, which does not
     * use SSL so the connection will never work
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatProfileTransportClientCannotConnectToNoSslProfile() throws Exception {
        Settings settings = ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient-client-profile.jks", "testclient-client-profile");
        try (TransportClient transportClient = createTransportClient(settings)) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_ssl")));
            transportClient.admin().cluster().prepareHealth().get();
        }
    }

    /**
     * Uses a transport client with SSL disabled. This test connects to the no_ssl profile, which should always succeed
     */
    @Test
    public void testThatTransportClientCanConnectToNoSslProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).addPlugin(ShieldPlugin.class).addPlugin(licensePluginClass()).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_ssl")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with SSL disabled. This test connects to the default profile, which should always fail
     * as a non-ssl transport client cannot connect to a ssl profile
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatTransportClientCannotConnectToDefaultProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(internalCluster().getInstance(Transport.class).boundAddress().boundAddress());
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with SSL disabled. This test connects to the client profile, which should always fail
     * as a non-ssl transport client cannot connect to a ssl profile
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatTransportClientCannotConnectToClientProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("client")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with SSL disabled. This test connects to the no_client_auth profile, which should always fail
     * as a non-ssl transport client cannot connect to a ssl profile
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatTransportClientCannotConnectToNoClientAuthProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_client_auth")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with a custom truststore; this truststore truststore-testnode-only only trusts the testnode
     * certificate and contains no other certification. This test connects to the no_client_auth profile, which uses
     * the testnode certificate and does not require to present a certificate, so this connection should always succeed
     */
    @Test
    public void testThatTransportClientWithOnlyTruststoreCanConnectToNoClientAuthProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("shield.ssl.truststore.path", getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/truststore-testnode-only.jks"))
                .put("shield.ssl.truststore.password", "truststore-testnode-only")
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).addPlugin(ShieldPlugin.class).addPlugin(licensePluginClass()).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_client_auth")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with a custom truststore; this truststore truststore-testnode-only only trusts the testnode
     * certificate and contains no other certification. This test connects to the client profile, which uses
     * the testnode certificate and requires the client to present a certificate, so this connection will never work as
     * the client has no certificate to present
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatTransportClientWithOnlyTruststoreCannotConnectToClientProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("shield.ssl.truststore.path", getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/truststore-testnode-only.jks"))
                .put("shield.ssl.truststore.password", "truststore-testnode-only")
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("client")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with a custom truststore; this truststore truststore-testnode-only only trusts the testnode
     * certificate and contains no other certification. This test connects to the default profile, which uses
     * the testnode certificate and requires the client to present a certificate, so this connection will never work as
     * the client has no certificate to present
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatTransportClientWithOnlyTruststoreCannotConnectToDefaultProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("shield.ssl.truststore.path", getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/truststore-testnode-only.jks"))
                .put("shield.ssl.truststore.password", "truststore-testnode-only")
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(internalCluster().getInstance(Transport.class).boundAddress().boundAddress());
                    assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with a custom truststore; this truststore truststore-testnode-only only trusts the testnode
     * certificate and contains no other certification. This test connects to the no_ssl profile, which does not use
     * SSL so the connection should never succeed
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatTransportClientWithOnlyTruststoreCannotConnectToNoSslProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("shield.ssl.truststore.path", getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/truststore-testnode-only.jks"))
                .put("shield.ssl.truststore.password", "truststore-testnode-only")
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_ssl")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with the default JDK truststore; this truststore only trusts the known good public
     * certificate authorities. This test connects to the default profile, which uses a self-signed certificate that
     * will never be trusted by the default truststore so the connection should always fail
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatSSLTransportClientWithNoTruststoreCannotConnectToDefaultProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(internalCluster().getInstance(Transport.class).boundAddress().boundAddress());
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with the default JDK truststore; this truststore only trusts the known good public
     * certificate authorities. This test connects to the client profile, which uses a self-signed certificate that
     * will never be trusted by the default truststore so the connection should always fail
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatSSLTransportClientWithNoTruststoreCannotConnectToClientProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("client")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with the default JDK truststore; this truststore only trusts the known good public
     * certificate authorities. This test connects to the no_client_auth profile, which uses a self-signed certificate that
     * will never be trusted by the default truststore so the connection should always fail
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatSSLTransportClientWithNoTruststoreCannotConnectToNoClientAuthProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_client_auth")));
            assertGreenClusterState(transportClient);
        }
    }

    /**
     * Uses a transport client with the default JDK truststore; this truststore only trusts the known good public
     * certificate authorities. This test connects to the no_ssl profile, which does not use SSL so the connection
     * will not work
     */
    @Test(expected = NoNodeAvailableException.class)
    public void testThatSSLTransportClientWithNoTruststoreCannotConnectToNoSslProfile() throws Exception {
        Settings settings = settingsBuilder()
                .put("shield.user", DEFAULT_USER_NAME + ":" + DEFAULT_PASSWORD)
                .put("cluster.name", internalCluster().getClusterName())
                .put("shield.transport.ssl", true)
                .put("path.home", createTempDir())
                .build();
        try (TransportClient transportClient = TransportClient.builder().settings(settings).loadConfigSettings(false).build()) {
            transportClient.addTransportAddress(new InetSocketTransportAddress(InetAddress.getLoopbackAddress(), getProfilePort("no_ssl")));
            assertGreenClusterState(transportClient);
        }
    }

    private static int getProfilePort(String profile) {
        TransportAddress transportAddress = internalCluster().getInstance(Transport.class).profileBoundAddresses().get(profile).boundAddress();
        assert transportAddress instanceof InetSocketTransportAddress;
        return ((InetSocketTransportAddress)transportAddress).address().getPort();
    }
}
