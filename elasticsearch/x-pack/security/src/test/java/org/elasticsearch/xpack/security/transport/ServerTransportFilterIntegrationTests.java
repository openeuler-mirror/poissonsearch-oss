/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.transport;

import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.MasterNotDiscoveredException;
import org.elasticsearch.node.MockNode;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.SecurityIntegTestCase;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.authc.file.FileRealm;
import org.elasticsearch.xpack.ssl.SSLClientAuth;
import org.junit.BeforeClass;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static java.util.Collections.singletonMap;

import static org.elasticsearch.test.SecuritySettingsSource.getSSLSettingsForStore;
import static org.elasticsearch.xpack.security.test.SecurityTestUtils.writeFile;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;

public class ServerTransportFilterIntegrationTests extends SecurityIntegTestCase {
    private static int randomClientPort;

    @BeforeClass
    public static void getRandomPort() {
        randomClientPort = randomIntBetween(49000, 65500); // ephemeral port
    }

    @Override
    protected boolean sslTransportEnabled() {
        return true;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder settingsBuilder = Settings.builder();
        String randomClientPortRange = randomClientPort + "-" + (randomClientPort+100);

        Path store;
        try {
            store = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks");
            assertThat(Files.exists(store), is(true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (sslTransportEnabled()) {
            settingsBuilder.put("transport.profiles.client.xpack.security.truststore.path", store) // settings for client truststore
                           .put("transport.profiles.client.xpack.security.truststore.password", "testnode")
                           .put("xpack.ssl.client_authentication", SSLClientAuth.REQUIRED);
        }

        return settingsBuilder
                .put(super.nodeSettings(nodeOrdinal))
                .put("transport.profiles.default.xpack.security.type", "node")
                .put("transport.profiles.client.xpack.security.type", "client")
                .put("transport.profiles.client.port", randomClientPortRange)
                // make sure this is "localhost", no matter if ipv4 or ipv6, but be consistent
                .put("transport.profiles.client.bind_host", "localhost")
                .put("xpack.security.audit.enabled", false)
                .build();
    }

    public void testThatConnectionToServerTypeConnectionWorks() throws IOException {
        Path home = createTempDir();
        Path xpackConf = home.resolve("config").resolve(XPackPlugin.NAME);
        Files.createDirectories(xpackConf);
        writeFile(xpackConf, "system_key", systemKey());

        Transport transport = internalCluster().getDataNodeInstance(Transport.class);
        TransportAddress transportAddress = transport.boundAddress().publishAddress();
        assertThat(transportAddress, instanceOf(InetSocketTransportAddress.class));
        InetSocketAddress inetSocketAddress = ((InetSocketTransportAddress) transportAddress).address();
        String unicastHost = NetworkAddress.format(inetSocketAddress);

        // test that starting up a node works
        Settings nodeSettings = Settings.builder()
                .put(getSSLSettingsForStore("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks", "testnode"))
                .put("node.name", "my-test-node")
                .put("network.host", "localhost")
                .put("cluster.name", internalCluster().getClusterName())
                .put("discovery.zen.ping.unicast.hosts", unicastHost)
                .put("xpack.security.transport.ssl.enabled", sslTransportEnabled())
                .put("xpack.security.audit.enabled", false)
                .put("path.home", home)
                .put(NetworkModule.HTTP_ENABLED.getKey(), false)
                .put(Node.NODE_MASTER_SETTING.getKey(), false)
                .build();
        try (Node node = new MockNode(nodeSettings, Collections.singletonList(XPackPlugin.class))) {
            node.start();
            assertGreenClusterState(node.client());
        }
    }

    public void testThatConnectionToClientTypeConnectionIsRejected() throws IOException {
        Path home = createTempDir();
        Path xpackConf = home.resolve("config").resolve(XPackPlugin.NAME);
        Files.createDirectories(xpackConf);
        writeFile(xpackConf, "system_key", systemKey());
        writeFile(xpackConf, "users", configUsers());
        writeFile(xpackConf, "users_roles", configUsersRoles());
        writeFile(xpackConf, "roles.yml", configRoles());

        Transport transport = internalCluster().getDataNodeInstance(Transport.class);
        TransportAddress transportAddress = transport.profileBoundAddresses().get("client").publishAddress();
        assertThat(transportAddress, instanceOf(InetSocketTransportAddress.class));
        InetSocketAddress inetSocketAddress = ((InetSocketTransportAddress) transportAddress).address();
        int port = inetSocketAddress.getPort();

        // test that starting up a node works
        Settings nodeSettings = Settings.builder()
                .put("xpack.security.authc.realms.file.type", FileRealm.TYPE)
                .put("xpack.security.authc.realms.file.order", 0)
                .put(getSSLSettingsForStore("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks", "testnode"))
                .put("node.name", "my-test-node")
                .put(Security.USER_SETTING.getKey(), "test_user:changeme")
                .put("cluster.name", internalCluster().getClusterName())
                .put("discovery.zen.ping.unicast.hosts", "localhost:" + randomClientPort)
                .put("xpack.security.transport.ssl.enabled", sslTransportEnabled())
                .put("xpack.security.audit.enabled", false)
                .put(NetworkModule.HTTP_ENABLED.getKey(), false)
                .put("discovery.initial_state_timeout", "2s")
                .put("path.home", home)
                .put(Node.NODE_MASTER_SETTING.getKey(), false)
                .build();
        try (Node node = new MockNode(nodeSettings, Collections.singletonList(XPackPlugin.class))) {
            node.start();

            // assert that node is not connected by waiting for the timeout
            try {
                // updating cluster settings requires a master. since the node should not be able to
                // connect to the cluster, there should be no master, and therefore this
                // operation should fail. we can't use cluster health/stats here to and
                // wait for a timeout, because as long as the node is not connected to the cluster
                // the license is disabled and therefore blocking health & stats calls.
                node.client().admin().cluster().prepareUpdateSettings()
                        .setTransientSettings(singletonMap("key", "value"))
                        .setMasterNodeTimeout(TimeValue.timeValueSeconds(2))
                        .get();
                fail("Expected to fail update settings as the node should not be able to connect to the cluster, cause there should be no" +
                        " master");
            } catch (MasterNotDiscoveredException e) {
                // expected
                logger.error("expected exception", e);
            }
        }
    }

}
