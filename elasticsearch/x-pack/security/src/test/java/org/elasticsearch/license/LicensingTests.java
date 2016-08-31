/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import java.util.ArrayList;
import java.util.Collection;

import org.apache.http.message.BasicHeader;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsIndices;
import org.elasticsearch.action.admin.cluster.stats.ClusterStatsResponse;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.transport.NoNodeAvailableException;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.SecurityIntegTestCase;
import org.elasticsearch.test.SecuritySettingsSource;
import org.elasticsearch.transport.Netty3Plugin;
import org.elasticsearch.transport.Netty4Plugin;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.TestXPackTransportClient;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.action.user.GetUsersResponse;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.security.client.SecurityClient;
import org.junit.Before;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class LicensingTests extends SecurityIntegTestCase {
    public static final String ROLES =
        SecuritySettingsSource.DEFAULT_ROLE + ":\n" +
            "  cluster: [ all ]\n" +
            "  indices:\n" +
            "    - names: '*'\n" +
            "      privileges: [manage]\n" +
            "    - names: '/.*/'\n" +
            "      privileges: [write]\n" +
            "    - names: 'test'\n" +
            "      privileges: [read]\n" +
            "    - names: 'test1'\n" +
            "      privileges: [read]\n" +
            "\n" +
            "role_a:\n" +
            "  indices:\n" +
            "    - names: 'a'\n" +
            "      privileges: [all]\n" +
            "\n" +
            "role_b:\n" +
            "  indices:\n" +
            "    - names: 'b'\n" +
            "      privileges: [all]\n";

    public static final String USERS =
        SecuritySettingsSource.CONFIG_STANDARD_USER +
            "user_a:{plain}passwd\n" +
            "user_b:{plain}passwd\n";

    public static final String USERS_ROLES =
        SecuritySettingsSource.CONFIG_STANDARD_USER_ROLES +
            "role_a:user_a,user_b\n" +
            "role_b:user_b\n";

    @Override
    protected String configRoles() {
        return ROLES;
    }

    @Override
    protected String configUsers() {
        return USERS;
    }

    @Override
    protected String configUsersRoles() {
        return USERS_ROLES;
    }

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
            .put(NetworkModule.HTTP_ENABLED.getKey(), true)
            .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        ArrayList<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(Netty3Plugin.class); // for http
        plugins.add(Netty4Plugin.class); // for http
        return plugins;
    }

    @Before
    public void resetLicensing() {
        enableLicensing();
    }

    public void testEnableDisableBehaviour() throws Exception {
        IndexResponse indexResponse = index("test", "type", jsonBuilder()
            .startObject()
            .field("name", "value")
            .endObject());
        assertEquals(DocWriteResponse.Result.CREATED, indexResponse.getResult());


        indexResponse = index("test1", "type", jsonBuilder()
            .startObject()
            .field("name", "value1")
            .endObject());
        assertEquals(DocWriteResponse.Result.CREATED, indexResponse.getResult());

        refresh();

        Client client = internalCluster().transportClient();

        disableLicensing();

        assertElasticsearchSecurityException(() -> client.admin().indices().prepareStats().get());
        assertElasticsearchSecurityException(() -> client.admin().cluster().prepareClusterStats().get());
        assertElasticsearchSecurityException(() -> client.admin().cluster().prepareHealth().get());
        assertElasticsearchSecurityException(() -> client.admin().cluster().prepareNodesStats().get());

        enableLicensing(randomFrom(License.OperationMode.values()));

        IndicesStatsResponse indicesStatsResponse = client.admin().indices().prepareStats().get();
        assertNoFailures(indicesStatsResponse);

        ClusterStatsResponse clusterStatsNodeResponse = client.admin().cluster().prepareClusterStats().get();
        assertThat(clusterStatsNodeResponse, notNullValue());
        ClusterStatsIndices indices = clusterStatsNodeResponse.getIndicesStats();
        assertThat(indices, notNullValue());
        assertThat(indices.getIndexCount(), greaterThanOrEqualTo(2));

        ClusterHealthResponse clusterIndexHealth = client.admin().cluster().prepareHealth().get();
        assertThat(clusterIndexHealth, notNullValue());

        NodesStatsResponse nodeStats = client.admin().cluster().prepareNodesStats().get();
        assertThat(nodeStats, notNullValue());
    }

    public void testRestAuthenticationByLicenseType() throws Exception {
        Response response = getRestClient().performRequest("GET", "/");
        // the default of the licensing tests is basic
        assertThat(response.getStatusLine().getStatusCode(), is(200));
        ResponseException e = expectThrows(ResponseException.class,
                () -> getRestClient().performRequest("GET", "/_xpack/security/_authenticate"));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), is(403));

        // generate a new license with a mode that enables auth
        License.OperationMode mode = randomFrom(License.OperationMode.GOLD, License.OperationMode.TRIAL,
            License.OperationMode.PLATINUM, License.OperationMode.STANDARD);
        enableLicensing(mode);
        e = expectThrows(ResponseException.class, () -> getRestClient().performRequest("GET", "/"));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), is(401));
        e = expectThrows(ResponseException.class, () -> getRestClient().performRequest("GET", "/_xpack/security/_authenticate"));
        assertThat(e.getResponse().getStatusLine().getStatusCode(), is(401));

        final String basicAuthValue = UsernamePasswordToken.basicAuthHeaderValue(SecuritySettingsSource.DEFAULT_USER_NAME,
                new SecuredString(SecuritySettingsSource.DEFAULT_PASSWORD.toCharArray()));
        response = getRestClient().performRequest("GET", "/", new BasicHeader("Authorization", basicAuthValue));
        assertThat(response.getStatusLine().getStatusCode(), is(200));
        response = getRestClient()
                .performRequest("GET", "/_xpack/security/_authenticate", new BasicHeader("Authorization", basicAuthValue));
        assertThat(response.getStatusLine().getStatusCode(), is(200));

    }

    public void testSecurityActionsByLicenseType() throws Exception {
        // security actions should not work!
        try (TransportClient client = new TestXPackTransportClient(internalCluster().transportClient().settings())) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            new SecurityClient(client).prepareGetUsers().get();
            fail("security actions should not be enabled!");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.status(), is(RestStatus.FORBIDDEN));
            assertThat(e.getMessage(), containsString("non-compliant"));
        }

        // enable a license that enables security
        License.OperationMode mode = randomFrom(License.OperationMode.GOLD, License.OperationMode.TRIAL,
                License.OperationMode.PLATINUM, License.OperationMode.STANDARD);
        enableLicensing(mode);
        // security actions should not work!
        try (TransportClient client = new TestXPackTransportClient(internalCluster().transportClient().settings())) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            GetUsersResponse response = new SecurityClient(client).prepareGetUsers().get();
            assertNotNull(response);
        }
    }

    public void testTransportClientAuthenticationByLicenseType() throws Exception {
        Settings.Builder builder = Settings.builder()
            .put(internalCluster().transportClient().settings());
        // remove user info
        builder.remove(Security.USER_SETTING.getKey());
        builder.remove(ThreadContext.PREFIX + "." + UsernamePasswordToken.BASIC_AUTH_HEADER);

        // basic has no auth
        try (TransportClient client = new TestXPackTransportClient(builder.build())) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            assertGreenClusterState(client);
        }

        // enable a license that enables security
        License.OperationMode mode = randomFrom(License.OperationMode.GOLD, License.OperationMode.TRIAL,
            License.OperationMode.PLATINUM, License.OperationMode.STANDARD);
        enableLicensing(mode);

        try (TransportClient client = new TestXPackTransportClient(builder.build())) {
            client.addTransportAddress(internalCluster().getDataNodeInstance(Transport.class).boundAddress().publishAddress());
            client.admin().cluster().prepareHealth().get();
            fail("should not have been able to connect to a node!");
        } catch (NoNodeAvailableException e) {
            // expected
        }
    }

    private static void assertElasticsearchSecurityException(ThrowingRunnable runnable) {
        ElasticsearchSecurityException ee = expectThrows(ElasticsearchSecurityException.class, runnable);
        assertThat(ee.getHeader("es.license.expired.feature"), hasItem(XPackPlugin.SECURITY));
        assertThat(ee.status(), is(RestStatus.FORBIDDEN));
    }

    public static void disableLicensing() {
        disableLicensing(License.OperationMode.BASIC);
    }

    public static void disableLicensing(License.OperationMode operationMode) {
        for (XPackLicenseState licenseState : internalCluster().getInstances(XPackLicenseState.class)) {
            licenseState.update(operationMode, false);
        }
    }

    public static void enableLicensing() {
        enableLicensing(License.OperationMode.BASIC);
    }

    public static void enableLicensing(License.OperationMode operationMode) {
        for (XPackLicenseState licenseState : internalCluster().getInstances(XPackLicenseState.class)) {
            licenseState.update(operationMode, true);
        }
    }
}
