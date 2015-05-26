/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.pki;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.node.Node;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.authc.support.UsernamePasswordToken;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.ShieldIntegrationTest;
import org.elasticsearch.test.ShieldSettingsSource;
import org.elasticsearch.test.rest.client.http.HttpRequestBuilder;
import org.elasticsearch.test.rest.client.http.HttpResponse;
import org.junit.Test;

import static org.hamcrest.Matchers.*;

@ClusterScope(numClientNodes = 0, numDataNodes = 1)
public class PkiWithoutSSLTests extends ShieldIntegrationTest {

    @Override
    public boolean sslTransportEnabled() {
        return false;
    }

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(Node.HTTP_ENABLED, true)
                .put("shield.authc.realms.pki1.type", "pki")
                .put("shield.authc.realms.pki1.order", "0")
                .build();
    }

    @Test
    public void testThatTransportClientWorks() {
        Client client = internalCluster().transportClient();
        assertGreenClusterState(client);
    }

    @Test
    public void testThatHttpWorks() throws Exception {
        HttpServerTransport httpServerTransport = internalCluster().getDataNodeInstance(HttpServerTransport.class);
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpRequestBuilder requestBuilder = new HttpRequestBuilder(httpClient)
                    .httpTransport(httpServerTransport)
                    .method("GET")
                    .path("/_nodes");
            requestBuilder.addHeader(UsernamePasswordToken.BASIC_AUTH_HEADER, UsernamePasswordToken.basicAuthHeaderValue(ShieldSettingsSource.DEFAULT_USER_NAME, new SecuredString(ShieldSettingsSource.DEFAULT_PASSWORD.toCharArray())));
            HttpResponse response = requestBuilder.execute();
            assertThat(response.getStatusCode(), is(200));
        }
    }
}
