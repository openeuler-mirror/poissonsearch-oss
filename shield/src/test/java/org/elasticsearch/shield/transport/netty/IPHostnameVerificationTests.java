/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.netty;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ShieldIntegTestCase;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.hamcrest.CoreMatchers.is;

public class IPHostnameVerificationTests extends ShieldIntegTestCase {
    Path keystore;

    @Override
    protected boolean sslTransportEnabled() {
        return true;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings settings = super.nodeSettings(nodeOrdinal);
        // The default Unicast test behavior is to use 'localhost' with the port number. For this test we need to use IP
        String[] unicastAddresses = settings.getAsArray("discovery.zen.ping.unicast.hosts");
        for (int i = 0; i < unicastAddresses.length; i++) {
            String address = unicastAddresses[i];
            unicastAddresses[i] = address.replace("localhost", "127.0.0.1");
        }

        Settings.Builder settingsBuilder = settingsBuilder()
                .put(settings)
                .putArray("discovery.zen.ping.unicast.hosts", unicastAddresses);

        try {
            //This keystore uses a cert with a CN of "Elasticsearch Test Node" and IPv4+IPv6 ip addresses as SubjectAlternativeNames
            keystore = getDataPath("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode-ip-only.jks");
            assertThat(Files.exists(keystore), is(true));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return settingsBuilder.put("shield.ssl.keystore.path", keystore.toAbsolutePath()) // settings for client truststore
                .put("shield.ssl.keystore.password", "testnode-ip-only")
                .put("shield.ssl.truststore.path", keystore.toAbsolutePath()) // settings for client truststore
                .put("shield.ssl.truststore.password", "testnode-ip-only")
                .put("transport.host", "127.0.0.1")
                .put("network.host", "127.0.0.1")
                .put("shield.ssl.client.auth", "false")
                .put(ShieldNettyTransport.HOSTNAME_VERIFICATION_SETTING, true)
                .put(ShieldNettyTransport.HOSTNAME_VERIFICATION_RESOLVE_NAME_SETTING, false)
                .build();
    }

    @Override
    protected Settings transportClientSettings() {
        return settingsBuilder().put(super.transportClientSettings())
                .put(ShieldNettyTransport.HOSTNAME_VERIFICATION_SETTING, true)
                .put(ShieldNettyTransport.HOSTNAME_VERIFICATION_RESOLVE_NAME_SETTING, false)
                .put("shield.ssl.keystore.path", keystore.toAbsolutePath())
                .put("shield.ssl.keystore.password", "testnode-ip-only")
                .put("shield.ssl.truststore.path", keystore.toAbsolutePath())
                .put("shield.ssl.truststore.password", "testnode-ip-only")
                .build();
    }

    public void testTransportClientConnectionWorksWithIPOnlyHostnameVerification() throws Exception {
        Client client = internalCluster().transportClient();
        assertGreenClusterState(client);
    }
}
