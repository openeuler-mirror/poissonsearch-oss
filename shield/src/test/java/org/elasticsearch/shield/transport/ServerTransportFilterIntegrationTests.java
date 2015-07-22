/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport;

import com.google.common.collect.ImmutableMap;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.discovery.MasterNotDiscoveredException;
import org.elasticsearch.license.plugin.LicensePlugin;
import org.elasticsearch.node.Node;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.authc.esusers.ESUsersRealm;
import org.elasticsearch.shield.crypto.InternalCryptoService;
import org.elasticsearch.test.ShieldIntegrationTest;
import org.elasticsearch.test.ShieldSettingsSource;
import org.elasticsearch.transport.Transport;
import org.junit.BeforeClass;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.node.NodeBuilder.nodeBuilder;
import static org.elasticsearch.shield.test.ShieldTestUtils.createFolder;
import static org.elasticsearch.shield.test.ShieldTestUtils.writeFile;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;

public class ServerTransportFilterIntegrationTests extends ShieldIntegrationTest {

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
        Settings.Builder settingsBuilder = settingsBuilder();
        String randomClientPortRange = randomClientPort + "-" + (randomClientPort+100);

        Path store;
        try {
            store = getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks");
            assertThat(Files.exists(store), is(true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (sslTransportEnabled()) {
            settingsBuilder.put("transport.profiles.client.shield.truststore.path", store) // settings for client truststore
                           .put("transport.profiles.client.shield.truststore.password", "testnode")
                           .put("shield.transport.ssl", true);
        }

        return settingsBuilder
                .put(super.nodeSettings(nodeOrdinal))
                .put("transport.profiles.default.shield.type", "node")
                .put("transport.profiles.client.shield.type", "client")
                .put("transport.profiles.client.port", randomClientPortRange)
                .put("transport.profiles.client.bind_host", "localhost") // make sure this is "localhost", no matter if ipv4 or ipv6, but be consistent
                .put("shield.audit.enabled", false)
                .build();
    }

    @Test
    public void testThatConnectionToServerTypeConnectionWorks() {
        Settings dataNodeSettings = internalTestCluster().getDataNodeInstance(Settings.class);
        String systemKeyFile = dataNodeSettings.get(InternalCryptoService.FILE_SETTING);

        Transport transport = internalTestCluster().getDataNodeInstance(Transport.class);
        TransportAddress transportAddress = transport.boundAddress().publishAddress();
        assertThat(transportAddress, instanceOf(InetSocketTransportAddress.class));
        InetSocketAddress inetSocketAddress = ((InetSocketTransportAddress) transportAddress).address();
        String unicastHost = inetSocketAddress.getHostName() + ":" + inetSocketAddress.getPort();

        // test that starting up a node works
        Settings nodeSettings = settingsBuilder()
                .put(ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks", "testnode"))
                .put("plugin.types", ShieldPlugin.class.getName() + "," + LicensePlugin.class.getName())
                .put("node.mode", "network")
                .put("node.name", "my-test-node")
                .put("network.host", "localhost")
                .put("cluster.name", internalTestCluster().getClusterName())
                .put("discovery.zen.ping.multicast.enabled", false)
                .put("discovery.zen.ping.unicast.hosts", unicastHost)
                .put("shield.transport.ssl", sslTransportEnabled())
                .put("shield.audit.enabled", false)
                .put("path.home", createTempDir())
                .put(Node.HTTP_ENABLED, false)
                .put(InternalCryptoService.FILE_SETTING, systemKeyFile)
                .build();
        try (Node node = nodeBuilder().client(true).settings(nodeSettings).node()) {
            assertGreenClusterState(node.client());
        }
    }

    @Test
    public void testThatConnectionToClientTypeConnectionIsRejected() {
        Settings dataNodeSettings = internalTestCluster().getDataNodeInstance(Settings.class);
        String systemKeyFile = dataNodeSettings.get(InternalCryptoService.FILE_SETTING);

        Path folder = createFolder(createTempDir(), getClass().getSimpleName() + "-" + randomAsciiOfLength(10));

        // test that starting up a node works
        Settings nodeSettings = settingsBuilder()
                .put("shield.authc.realms.esusers.type", ESUsersRealm.TYPE)
                .put("shield.authc.realms.esusers.order", 0)
                .put("shield.authc.realms.esusers.files.users", writeFile(folder, "users", configUsers()))
                .put("shield.authc.realms.esusers.files.users_roles", writeFile(folder, "users_roles", configUsersRoles()))
                .put("shield.authz.store.files.roles", writeFile(folder, "roles.yml", configRoles()))
                .put(ShieldSettingsSource.getSSLSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks", "testnode"))
                .put("node.mode", "network")
                .put("node.name", "my-test-node")
                .put("shield.user", "test_user:changeme")
                .put("cluster.name", internalTestCluster().getClusterName())
                .put("discovery.zen.ping.multicast.enabled", false)
                .put("discovery.zen.ping.unicast.hosts", "localhost:" + randomClientPort)
                .put("shield.transport.ssl", sslTransportEnabled())
                .put("shield.audit.enabled", false)
                .put(Node.HTTP_ENABLED, false)
                .put(InternalCryptoService.FILE_SETTING, systemKeyFile)
                .put("discovery.initial_state_timeout", "2s")
                .put("path.home", createTempDir())
                .build();
        try (Node node = nodeBuilder().client(true).settings(nodeSettings).build()) {
            node.start();

            // assert that node is not connected by waiting for the timeout
            try {
                // updating cluster settings requires a master. since the node should not be able to
                // connect to the cluster, there should be no master, and therefore this
                // operation should fail. we can't use cluster health/stats here to and
                // wait for a timeout, because as long as the node is not connected to the cluster
                // the license is disabled and therefore blocking health & stats calls.
                node.client().admin().cluster().prepareUpdateSettings()
                        .setTransientSettings(ImmutableMap.of("key", "value"))
                        .setMasterNodeTimeout(TimeValue.timeValueSeconds(2))
                        .get();
                fail("Expected to fail update settings as the node should not be able to connect to the cluster, and therefore there should be no master");
            } catch (MasterNotDiscoveredException e) {
                // expected
                logger.error("expected:  " + e);
            }
        }
    }

}
