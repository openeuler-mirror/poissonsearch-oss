/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.test;

import com.carrotsearch.hppc.cursors.ObjectObjectCursor;
import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.PluginInfo;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.audit.index.IndexAuditTrail;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.rules.ExternalResource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoTimeout;
import static org.hamcrest.CoreMatchers.is;

/**
 * Base class to run tests against a cluster with shield installed.
 * The default {@link org.elasticsearch.test.ESIntegTestCase.Scope} is {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE},
 * meaning that all subclasses that don't specify a different scope will share the same cluster with shield installed.
 * @see org.elasticsearch.test.ShieldSettingsSource
 */
public abstract class ShieldIntegTestCase extends ESIntegTestCase {

    private static ShieldSettingsSource SHIELD_DEFAULT_SETTINGS;

    //UnicastZen requires the number of nodes in a cluster to generate the unicast configuration.
    //The number of nodes is randomized though, but we can predict what the maximum number of nodes will be
    //and configure them all in unicast.hosts
    private static int maxNumberOfNodes() {
        ClusterScope clusterScope = ShieldIntegTestCase.class.getAnnotation(ClusterScope.class);
        if (clusterScope == null) {
            return InternalTestCluster.DEFAULT_MAX_NUM_DATA_NODES + InternalTestCluster.DEFAULT_MAX_NUM_CLIENT_NODES;
        } else {
            if (clusterScope.numClientNodes() < 0) {
                return clusterScope.maxNumDataNodes() + InternalTestCluster.DEFAULT_MAX_NUM_CLIENT_NODES;
            } else {
                return clusterScope.maxNumDataNodes() + clusterScope.numClientNodes();
            }
        }
    }

    private static ClusterScope getAnnotation(Class<?> clazz) {
        if (clazz == Object.class || clazz == ShieldIntegTestCase.class) {
            return null;
        }
        ClusterScope annotation = clazz.getAnnotation(ClusterScope.class);
        if (annotation != null) {
            return annotation;
        }
        return getAnnotation(clazz.getSuperclass());
    }

    private Scope getCurrentClusterScope() {
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
    private CustomShieldSettingsSource customShieldSettingsSource = null;

    @BeforeClass
    public static void initDefaultSettings() {
        if (SHIELD_DEFAULT_SETTINGS == null) {
            SHIELD_DEFAULT_SETTINGS = new ShieldSettingsSource(maxNumberOfNodes(), randomBoolean(), createTempDir(), Scope.SUITE);
        }
    }

    /**
     * Set the static default settings to null to prevent a memory leak. The test framework also checks for memory leaks
     * and computes the size, this can cause issues when running with the security manager as it tries to do reflection
     * into protected sun packages.
     */
    @AfterClass
    public static void destroyDefaultSettings() {
        SHIELD_DEFAULT_SETTINGS = null;
    }

    @Rule
    //Rules are the only way to have something run before the before (final) method inherited from ESIntegTestCase
    public ExternalResource externalResource = new ExternalResource() {
        @Override
        protected void before() throws Throwable {
            Scope currentClusterScope = getCurrentClusterScope();
            switch(currentClusterScope) {
                case SUITE:
                    if (customShieldSettingsSource == null) {
                        customShieldSettingsSource = new CustomShieldSettingsSource(sslTransportEnabled(), createTempDir(), currentClusterScope);
                    }
                    break;
                case TEST:
                    customShieldSettingsSource = new CustomShieldSettingsSource(sslTransportEnabled(), createTempDir(), currentClusterScope);
                    break;
            }
        }
    };

    @Before
    //before methods from the superclass are run before this, which means that the current cluster is ready to go
    public void assertShieldIsInstalled() {
        NodesInfoResponse nodeInfos = client().admin().cluster().prepareNodesInfo().clear().setPlugins(true).get();
        for (NodeInfo nodeInfo : nodeInfos) {
            // TODO: disable this assertion for now, because the test framework randomly runs with mock plugins. Maybe we should run without mock plugins?
//            assertThat(nodeInfo.getPlugins().getInfos(), hasSize(2));
            Collection<String> pluginNames = Collections2.transform(nodeInfo.getPlugins().getInfos(), new Function<PluginInfo, String>() {
                @Override
                public String apply(PluginInfo pluginInfo) {
                    return pluginInfo.getName();
                }
            });
            assertThat("plugin [" + ShieldPlugin.NAME + "] not found in [" + pluginNames + "]", pluginNames.contains(ShieldPlugin.NAME), is(true));
            assertThat("plugin [" + licensePluginName() + "] not found in [" + pluginNames + "]", pluginNames.contains(licensePluginName()), is(true));
        }
    }

    @Override
    protected TestCluster buildTestCluster(Scope scope, long seed) throws IOException {
        // This overwrites the wipe logic of the test cluster to not remove the shield_audit_log template. By default all templates are removed
        // TODO: We should have the notion of a hidden template (like hidden index / type) that only gets removed when specifically mentioned.
        final TestCluster testCluster = super.buildTestCluster(scope, seed);
        return new ShieldWrappingCluster(seed, testCluster);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder().put(super.nodeSettings(nodeOrdinal))
                .put(customShieldSettingsSource.nodeSettings(nodeOrdinal))
                .build();
    }

    @Override
    protected Settings transportClientSettings() {
        return Settings.builder().put(super.transportClientSettings())
                .put(customShieldSettingsSource.transportClientSettings())
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(ShieldPlugin.class, licensePluginClass());
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();
    }

    @Override
    protected Settings externalClusterClientSettings() {
        return Settings.builder()
                .put("shield.user", ShieldSettingsSource.DEFAULT_USER_NAME + ":" + ShieldSettingsSource.DEFAULT_PASSWORD)
                .build();
    }

    /**
     * Allows for us to get the system key that is being used for the cluster
     * @return the system key bytes
     */
    protected byte[] systemKey() {
        return customShieldSettingsSource.systemKey();
    }

    /**
     * Allows to override the users config file when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String configUsers() {
        return SHIELD_DEFAULT_SETTINGS.configUsers();
    }

    /**
     * Allows to override the users_roles config file when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String configUsersRoles() {
        return SHIELD_DEFAULT_SETTINGS.configUsersRoles();
    }

    /**
     * Allows to override the roles config file when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String configRoles() {
        return SHIELD_DEFAULT_SETTINGS.configRoles();
    }

    /**
     * Allows to override the node client username (used while sending requests to the test cluster) when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String nodeClientUsername() {
        return SHIELD_DEFAULT_SETTINGS.nodeClientUsername();
    }

    /**
     * Allows to override the node client password (used while sending requests to the test cluster) when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected SecuredString nodeClientPassword() {
        return SHIELD_DEFAULT_SETTINGS.nodeClientPassword();
    }

    /**
     * Allows to override the transport client username (used while sending requests to the test cluster) when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected String transportClientUsername() {
        return SHIELD_DEFAULT_SETTINGS.transportClientUsername();
    }

    /**
     * Allows to override the transport client password (used while sending requests to the test cluster) when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected SecuredString transportClientPassword() {
        return SHIELD_DEFAULT_SETTINGS.transportClientPassword();
    }

    /**
     * Allows to control whether ssl is enabled or not on the transport layer when the {@link org.elasticsearch.test.ESIntegTestCase.ClusterScope} is set to
     * {@link org.elasticsearch.test.ESIntegTestCase.Scope#SUITE} or {@link org.elasticsearch.test.ESIntegTestCase.Scope#TEST}
     */
    protected boolean sslTransportEnabled() {
        return randomBoolean();
    }

    protected Class<? extends Plugin> licensePluginClass() {
        return SHIELD_DEFAULT_SETTINGS.licensePluginClass();
    }

    protected String licensePluginName() {
        return SHIELD_DEFAULT_SETTINGS.licensePluginName();
    }

    private class CustomShieldSettingsSource extends ShieldSettingsSource {
        private CustomShieldSettingsSource(boolean sslTransportEnabled, Path configDir, Scope scope) {
            super(maxNumberOfNodes(), sslTransportEnabled, configDir, scope);
        }

        @Override
        protected String configUsers() {
            return ShieldIntegTestCase.this.configUsers();
        }

        @Override
        protected String configUsersRoles() {
            return ShieldIntegTestCase.this.configUsersRoles();
        }

        @Override
        protected String configRoles() {
            return ShieldIntegTestCase.this.configRoles();
        }

        @Override
        protected String nodeClientUsername() {
            return ShieldIntegTestCase.this.nodeClientUsername();
        }

        @Override
        protected SecuredString nodeClientPassword() {
            return ShieldIntegTestCase.this.nodeClientPassword();
        }

        @Override
        protected String transportClientUsername() {
            return ShieldIntegTestCase.this.transportClientUsername();
        }

        @Override
        protected SecuredString transportClientPassword() {
            return ShieldIntegTestCase.this.transportClientPassword();
        }

        @Override
        protected Class<? extends Plugin> licensePluginClass() {
            return ShieldIntegTestCase.this.licensePluginClass();
        }

        @Override
        public Collection<Class<? extends Plugin>> nodePlugins() {
            return ShieldIntegTestCase.this.nodePlugins();
        }

        @Override
        public Collection<Class<? extends Plugin>> transportClientPlugins() {
            return ShieldIntegTestCase.this.transportClientPlugins();
        }

        @Override
        protected String licensePluginName() {
            return ShieldIntegTestCase.this.licensePluginName();
        }
    }

    protected void assertGreenClusterState(Client client) {
        ClusterHealthResponse clusterHealthResponse = client.admin().cluster().prepareHealth().get();
        assertNoTimeout(clusterHealthResponse);
        assertThat(clusterHealthResponse.getStatus(), is(ClusterHealthStatus.GREEN));
    }

    protected static InternalTestCluster internalTestCluster() {
        return (InternalTestCluster) ((ShieldWrappingCluster) cluster()).testCluster;
    }

    @Override
    public ClusterService clusterService() {
        return internalTestCluster().clusterService();
    }

    // We need this custom impl, because we have custom wipe logic. We don't want the audit index templates to get deleted between tests
    private final class ShieldWrappingCluster extends TestCluster {

        private final TestCluster testCluster;

        private ShieldWrappingCluster(long seed, TestCluster testCluster) {
            super(seed);
            this.testCluster = testCluster;
        }

        @Override
        public void beforeTest(Random random, double transportClientRatio) throws IOException {
            testCluster.beforeTest(random, transportClientRatio);
        }

        @Override
        public void wipe() {
            wipeIndices("_all");
            wipeRepositories();

            if (size() > 0) {
                List<String> templatesToWipe = new ArrayList<>();
                ClusterState state = client().admin().cluster().prepareState().get().getState();
                for (ObjectObjectCursor<String, IndexTemplateMetaData> cursor : state.getMetaData().templates()) {
                    if (cursor.key.equals(IndexAuditTrail.INDEX_TEMPLATE_NAME)) {
                        continue;
                    }
                    templatesToWipe.add(cursor.key);
                }
                if (!templatesToWipe.isEmpty()) {
                    wipeTemplates(templatesToWipe.toArray(new String[templatesToWipe.size()]));
                }
            }
        }

        @Override
        public void afterTest() throws IOException {
            testCluster.afterTest();
        }

        @Override
        public Client client() {
            return testCluster.client();
        }

        @Override
        public int size() {
            return testCluster.size();
        }

        @Override
        public int numDataNodes() {
            return testCluster.numDataNodes();
        }

        @Override
        public int numDataAndMasterNodes() {
            return testCluster.numDataAndMasterNodes();
        }

        @Override
        public InetSocketAddress[] httpAddresses() {
            return testCluster.httpAddresses();
        }

        @Override
        public void close() throws IOException {
            testCluster.close();
        }

        @Override
        public void ensureEstimatedStats() {
            testCluster.ensureEstimatedStats();
        }

        @Override
        public String getClusterName() {
            return testCluster.getClusterName();
        }

        @Override
        public Iterator<Client> iterator() {
            return testCluster.iterator();
        }

    }
}
