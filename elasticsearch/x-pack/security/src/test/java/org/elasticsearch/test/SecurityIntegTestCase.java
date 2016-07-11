/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.test;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.common.network.NetworkAddress;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.transport.MockTcpTransportPlugin;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.security.Security;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.client.SecurityClient;
import org.elasticsearch.test.ESIntegTestCase.SuppressLocalMode;
import org.elasticsearch.test.transport.AssertingLocalTransport;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.xpack.XPackClient;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.security.transport.netty.SecurityNettyHttpServerTransport;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExternalResource;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoTimeout;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsCollectionContaining.hasItem;

/**
 * Base class to run tests against a cluster with X-Pack installed and security enabled.
 * The default {@link org.elasticsearch.test.ESIntegTestCase.Scope} is {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE}
 *
 * @see SecuritySettingsSource
 */
@SuppressLocalMode
public abstract class SecurityIntegTestCase extends ESIntegTestCase {

    private static SecuritySettingsSource SECURITY_DEFAULT_SETTINGS;

    //UnicastZen requires the number of nodes in a cluster to generate the unicast configuration.
    //The number of nodes is randomized though, but we can predict what the maximum number of nodes will be
    //and configure them all in unicast.hosts
    private static int maxNumberOfNodes() {
        ClusterScope clusterScope = SecurityIntegTestCase.class.getAnnotation(ClusterScope.class);
        if (clusterScope == null) {
            return InternalTestCluster.DEFAULT_HIGH_NUM_MASTER_NODES +
                    InternalTestCluster.DEFAULT_MAX_NUM_DATA_NODES +
                    InternalTestCluster.DEFAULT_MAX_NUM_CLIENT_NODES;
        } else {
            int clientNodes = clusterScope.numClientNodes();
            if (clientNodes < 0) {
                clientNodes = InternalTestCluster.DEFAULT_MAX_NUM_CLIENT_NODES;
            }
            int masterNodes = 0;
            if (clusterScope.supportsDedicatedMasters()) {
                masterNodes = InternalTestCluster.DEFAULT_HIGH_NUM_MASTER_NODES;
            }

            return masterNodes + clusterScope.maxNumDataNodes() + clientNodes;
        }
    }

    private static ClusterScope getAnnotation(Class<?> clazz) {
        if (clazz == Object.class || clazz == SecurityIntegTestCase.class) {
            return null;
        }
        ClusterScope annotation = clazz.getAnnotation(ClusterScope.class);
        if (annotation != null) {
            return annotation;
        }
        return getAnnotation(clazz.getSuperclass());
    }

    Scope getCurrentClusterScope() {
        return getCurrentClusterScope(this.getClass());
    }

    private static Scope getCurrentClusterScope(Class<?> clazz) {
        ClusterScope annotation = getAnnotation(clazz);
        return annotation == null ? Scope.SUITE : annotation.scope();
    }

    /**
     * Settings used when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     * so that some of the configuration parameters can be overridden through test instance methods, similarly
     * to how {@link #nodeSettings(int)} and {@link #transportClientSettings()} work.
     */
    private CustomSecuritySettingsSource customSecuritySettingsSource = null;

    @BeforeClass
    public static void initDefaultSettings() {
        if (SECURITY_DEFAULT_SETTINGS == null) {
            SECURITY_DEFAULT_SETTINGS = new SecuritySettingsSource(maxNumberOfNodes(), randomBoolean(), createTempDir(), Scope.SUITE);
        }
    }

    /**
     * Set the static default settings to null to prevent a memory leak. The test framework also checks for memory leaks
     * and computes the size, this can cause issues when running with the security manager as it tries to do reflection
     * into protected sun packages.
     */
    @AfterClass
    public static void destroyDefaultSettings() {
        SECURITY_DEFAULT_SETTINGS = null;
    }

    @Rule
    //Rules are the only way to have something run before the before (final) method inherited from ESIntegTestCase
    public ExternalResource externalResource = new ExternalResource() {
        @Override
        protected void before() throws Throwable {
            Scope currentClusterScope = getCurrentClusterScope();
            switch (currentClusterScope) {
                case SUITE:
                    if (customSecuritySettingsSource == null) {
                        customSecuritySettingsSource =
                                new CustomSecuritySettingsSource(sslTransportEnabled(), createTempDir(), currentClusterScope);
                    }
                    break;
                case TEST:
                    customSecuritySettingsSource =
                            new CustomSecuritySettingsSource(sslTransportEnabled(), createTempDir(), currentClusterScope);
                    break;
            }
        }
    };

    @Before
    //before methods from the superclass are run before this, which means that the current cluster is ready to go
    public void assertXPackIsInstalled() {
        NodesInfoResponse nodeInfos = client().admin().cluster().prepareNodesInfo().clear().setPlugins(true).get();
        for (NodeInfo nodeInfo : nodeInfos.getNodes()) {
            // TODO: disable this assertion for now, due to random runs with mock plugins. perhaps run without mock plugins?
//            assertThat(nodeInfo.getPlugins().getInfos(), hasSize(2));
            Collection<String> pluginNames =
                    nodeInfo.getPlugins().getPluginInfos().stream().map(p -> p.getName()).collect(Collectors.toList());
            assertThat("plugin [" + xpackPluginClass().getName() + "] not found in [" + pluginNames + "]", pluginNames,
                hasItem(xpackPluginClass().getName()));
        }
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
                .put(customSecuritySettingsSource.nodeSettings(nodeOrdinal))
                .build();
    }

    @Override
    protected Settings transportClientSettings() {
        return Settings.builder().put(super.transportClientSettings())
                .put(customSecuritySettingsSource.transportClientSettings())
                .build();
    }

    @Override
    protected boolean addMockTransportService() {
        return false; // security has its own transport service
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return customSecuritySettingsSource.nodePlugins();
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return customSecuritySettingsSource.transportClientPlugins();
    }

    @Override
    protected Settings externalClusterClientSettings() {
        return Settings.builder()
                .put(Security.USER_SETTING.getKey(), SecuritySettingsSource.DEFAULT_USER_NAME + ":"
                        + SecuritySettingsSource.DEFAULT_PASSWORD)
                .build();
    }

    /**
     * Allows for us to get the system key that is being used for the cluster
     *
     * @return the system key bytes
     */
    protected byte[] systemKey() {
        return customSecuritySettingsSource.systemKey();
    }

    /**
     * Allows to override the users config file when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String configUsers() {
        return SECURITY_DEFAULT_SETTINGS.configUsers();
    }

    /**
     * Allows to override the users_roles config file when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String configUsersRoles() {
        return SECURITY_DEFAULT_SETTINGS.configUsersRoles();
    }

    /**
     * Allows to override the roles config file when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String configRoles() {
        return SECURITY_DEFAULT_SETTINGS.configRoles();
    }

    /**
     * Allows to override the node client username (used while sending requests to the test cluster) when the
     * {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String nodeClientUsername() {
        return SECURITY_DEFAULT_SETTINGS.nodeClientUsername();
    }

    /**
     * Allows to override the node client password (used while sending requests to the test cluster) when the
     * {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected SecuredString nodeClientPassword() {
        return SECURITY_DEFAULT_SETTINGS.nodeClientPassword();
    }

    /**
     * Allows to override the transport client username (used while sending requests to the test cluster) when the
     * {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String transportClientUsername() {
        return SECURITY_DEFAULT_SETTINGS.transportClientUsername();
    }

    /**
     * Allows to override the transport client password (used while sending requests to the test cluster) when the
     * {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected SecuredString transportClientPassword() {
        return SECURITY_DEFAULT_SETTINGS.transportClientPassword();
    }

    /**
     * Allows to control whether ssl is enabled or not on the transport layer when the
     * {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected boolean sslTransportEnabled() {
        return randomBoolean();
    }

    protected Class<? extends XPackPlugin> xpackPluginClass() {
        return SECURITY_DEFAULT_SETTINGS.xpackPluginClass();
    }

    private class CustomSecuritySettingsSource extends SecuritySettingsSource {

        private CustomSecuritySettingsSource(boolean sslTransportEnabled, Path configDir, Scope scope) {
            super(maxNumberOfNodes(), sslTransportEnabled, configDir, scope);
        }

        @Override
        protected String configUsers() {
            return SecurityIntegTestCase.this.configUsers();
        }

        @Override
        protected String configUsersRoles() {
            return SecurityIntegTestCase.this.configUsersRoles();
        }

        @Override
        protected String configRoles() {
            return SecurityIntegTestCase.this.configRoles();
        }

        @Override
        protected String nodeClientUsername() {
            return SecurityIntegTestCase.this.nodeClientUsername();
        }

        @Override
        protected SecuredString nodeClientPassword() {
            return SecurityIntegTestCase.this.nodeClientPassword();
        }

        @Override
        protected String transportClientUsername() {
            return SecurityIntegTestCase.this.transportClientUsername();
        }

        @Override
        protected SecuredString transportClientPassword() {
            return SecurityIntegTestCase.this.transportClientPassword();
        }

        @Override
        protected Class<? extends XPackPlugin> xpackPluginClass() {
            return SecurityIntegTestCase.this.xpackPluginClass();
        }
    }

    protected void assertGreenClusterState(Client client) {
        ClusterHealthResponse clusterHealthResponse = client.admin().cluster().prepareHealth().get();
        assertNoTimeout(clusterHealthResponse);
        assertThat(clusterHealthResponse.getStatus(), is(ClusterHealthStatus.GREEN));
    }

    @Override
    protected Function<Client,Client> getClientWrapper() {
        Map<String, String> headers = Collections.singletonMap("Authorization",
                basicAuthHeaderValue(nodeClientUsername(), nodeClientPassword()));
        // we need to wrap node clients because we do not specify a user for nodes and all requests will use the system
        // user. This is ok for internal n2n stuff but the test framework does other things like wiping indices, repositories, etc
        // that the system user cannot do. so we wrap the node client with a user that can do these things since the client() calls
        // are randomized to return both node clients and transport clients
        // transport clients do not need to be wrapped since we specify the xpack.security.user setting that sets the default user to be
        // used for the transport client. If we did not set a default user then the transport client would not even be allowed
        // to connect
        return client -> (client instanceof NodeClient) ? client.filterWithHeader(headers) : client;
    }

    protected InternalClient internalClient() {
        return internalCluster().getInstance(InternalClient.class);
    }

    protected SecurityClient securityClient() {
        return securityClient(client());
    }

    public static SecurityClient securityClient(Client client) {
        return randomBoolean() ? new XPackClient(client).security() : new SecurityClient(client);
    }

    protected String getHttpURL() {
        boolean useSSL = false;
        final NodesInfoResponse nodeInfos = client().admin().cluster().prepareNodesInfo().get();
        final List<NodeInfo> nodes = nodeInfos.getNodes();
        assertTrue("there is at least one node", nodes.size() > 0);
        NodeInfo ni = randomFrom(nodes);
        useSSL = SecurityNettyHttpServerTransport.SSL_SETTING.get(ni.getSettings());
        TransportAddress publishAddress = ni.getHttp().address().publishAddress();
        assertEquals(1, publishAddress.uniqueAddressTypeId());
        InetSocketAddress address = ((InetSocketTransportAddress) publishAddress).address();
        return (useSSL ? "https://" : "http://") + NetworkAddress.format(address.getAddress()) + ":" + address.getPort();
    }
}
