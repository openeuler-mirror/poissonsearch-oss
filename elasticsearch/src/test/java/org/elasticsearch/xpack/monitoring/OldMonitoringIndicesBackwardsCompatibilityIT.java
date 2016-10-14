/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring;

import org.elasticsearch.AbstractOldXPackIndicesBackwardsCompatibilityTestCase;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.SecuritySettingsSource;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.monitoring.resolver.MonitoringIndexNameResolver;
import org.elasticsearch.xpack.monitoring.resolver.cluster.ClusterStateResolver;
import org.elasticsearch.xpack.monitoring.resolver.indices.IndexStatsResolver;
import org.elasticsearch.xpack.monitoring.resolver.indices.IndicesStatsResolver;
import org.elasticsearch.xpack.monitoring.resolver.node.NodeStatsResolver;
import org.elasticsearch.xpack.monitoring.resolver.shards.ShardsResolver;
import org.hamcrest.Matcher;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasKey;

/**
 * Tests for monitoring indexes created before 5.0.
 */
public class OldMonitoringIndicesBackwardsCompatibilityIT extends AbstractOldXPackIndicesBackwardsCompatibilityTestCase {
    private final boolean httpExporter = randomBoolean();

    @Override
    public Settings nodeSettings(int ord) {
        Settings.Builder settings = Settings.builder().put(super.nodeSettings(ord))
                .put(XPackSettings.MONITORING_ENABLED.getKey(), true)
                // Don't clean old monitoring indexes - we want to make sure we can load them
                .put(MonitoringSettings.HISTORY_DURATION.getKey(), TimeValue.timeValueHours(1000 * 365 * 24).getStringRep());

        if (httpExporter) {
            /* If we want to test the http exporter we have to create it but disable it. We need to create it so we don't use the default
             * local exporter and we have to disable it because we don't yet know the port we've bound to. We can only get that once
             * Elasticsearch starts so we'll enable the exporter then. */
            settings.put(NetworkModule.HTTP_ENABLED.getKey(), true);
            setupHttpExporter(settings, null);
        }
        return settings.build();
    }

    private void setupHttpExporter(Settings.Builder settings, Integer port) {
        Map<String, String> httpExporter = new HashMap<>();
        httpExporter.put("type", "http");
        httpExporter.put("enabled", port == null ? "false" : "true");
        httpExporter.put("host", "http://localhost:" + (port == null ? "does_not_matter" : port));
        httpExporter.put("auth.username", SecuritySettingsSource.DEFAULT_USER_NAME);
        httpExporter.put("auth.password", SecuritySettingsSource.DEFAULT_PASSWORD);

        settings.putProperties(httpExporter, k -> true, k -> MonitoringSettings.EXPORTERS_SETTINGS.getKey() + "my_exporter." + k);
    }

    @Override
    protected void checkVersion(Version version) throws Exception {
        if (version.before(Version.V_2_3_0)) {
            /* We can't do anything with indexes created before 2.3 so we just assert that we didn't delete them or do anything otherwise
             * crazy. */
            SearchResponse response = client().prepareSearch(".marvel-es-data").get();
            // 2.0.x didn't index the nodes info
            long expectedEsData = version.before(Version.V_2_1_0) ? 1 : 2;
            assertHitCount(response, expectedEsData);
            response = client().prepareSearch(".marvel-es-*").get();
            assertThat(response.getHits().totalHits(), greaterThanOrEqualTo(20L));
            return;
        }
        /* Indexes created from 2.3 onwards get aliased to the place they'd be if they were created in 5.0 so queries should just work.
         * Monitoring doesn't really have a Java API so we can't test that, but we can test that we write the data we expected to write. */

        if (httpExporter) {
            // If we're using the http exporter we need feed it the port and enable it
            NodesInfoResponse nodeInfos = client().admin().cluster().prepareNodesInfo().get();
            TransportAddress publishAddress = nodeInfos.getNodes().get(0).getHttp().address().publishAddress();
            InetSocketAddress address = publishAddress.address();
            Settings.Builder settings = Settings.builder();
            setupHttpExporter(settings, address.getPort());
            client().admin().cluster().prepareUpdateSettings().setTransientSettings(settings).get();
        }

        IndexStatsResolver resolver = new IndexStatsResolver(MonitoredSystem.ES, Settings.EMPTY);
        assertBusy(() -> assertTrue(client().admin().indices().prepareExists(resolver.indexPattern()).get().isExists()));
        SearchResponse firstIndexStats = search(resolver, greaterThanOrEqualTo(10L));

        // All the other aliases should have been created by now so we can assert that we have the data we saved in the bwc indexes
        SearchResponse firstShards = search(new ShardsResolver(MonitoredSystem.ES, Settings.EMPTY), greaterThanOrEqualTo(10L));
        SearchResponse firstIndicesStats = search(new IndicesStatsResolver(MonitoredSystem.ES, Settings.EMPTY), greaterThanOrEqualTo(3L));
        SearchResponse firstNodeStats = search(new NodeStatsResolver(MonitoredSystem.ES, Settings.EMPTY), greaterThanOrEqualTo(3L));
        SearchResponse firstClusterState = search(new ClusterStateResolver(MonitoredSystem.ES, Settings.EMPTY), greaterThanOrEqualTo(3L));

        ClusterStateResponse clusterStateResponse = client().admin().cluster().prepareState().clear().setNodes(true).get();
        final String masterNodeId = clusterStateResponse.getState().getNodes().getMasterNodeId();

        // Verify some stuff about the stuff in the backwards compatibility indexes
        Arrays.stream(firstIndexStats.getHits().hits()).forEach(hit -> checkIndexStats(version, hit.sourceAsMap()));
        Arrays.stream(firstShards.getHits().hits()).forEach(hit -> checkShards(version, hit.sourceAsMap()));
        Arrays.stream(firstIndicesStats.getHits().hits()).forEach(hit -> checkIndicesStats(version, hit.sourceAsMap()));
        Arrays.stream(firstNodeStats.getHits().hits()).forEach(hit -> checkNodeStats(version, masterNodeId, hit.sourceAsMap()));
        Arrays.stream(firstClusterState.getHits().hits()).forEach(hit -> checkClusterState(version, hit.sourceAsMap()));

        // Wait for monitoring to accumulate some data about the current cluster
        long indexStatsCount = firstIndexStats.getHits().totalHits();
        assertBusy(() -> search(new IndexStatsResolver(MonitoredSystem.ES, Settings.EMPTY),
                greaterThan(indexStatsCount)), 1, TimeUnit.MINUTES);
        assertBusy(() -> search(new ShardsResolver(MonitoredSystem.ES, Settings.EMPTY),
                greaterThan(firstShards.getHits().totalHits())), 1, TimeUnit.MINUTES);
        assertBusy(() -> search(new IndicesStatsResolver(MonitoredSystem.ES, Settings.EMPTY),
                greaterThan(firstIndicesStats.getHits().totalHits())), 1, TimeUnit.MINUTES);
        assertBusy(() -> search(new NodeStatsResolver(MonitoredSystem.ES, Settings.EMPTY),
                greaterThan(firstNodeStats.getHits().totalHits())), 1, TimeUnit.MINUTES);
        assertBusy(() -> search(new ClusterStateResolver(MonitoredSystem.ES, Settings.EMPTY),
                greaterThan(firstClusterState.getHits().totalHits())), 1, TimeUnit.MINUTES);
    }

    private SearchResponse search(MonitoringIndexNameResolver<?> resolver, Matcher<Long> hitCount) {
        SearchResponse response = client().prepareSearch(resolver.indexPattern()).setTypes(resolver.type(null)).get();
        assertThat(response.getHits().totalHits(), hitCount);
        return response;
    }

    private void checkIndexStats(final Version version, Map<String, Object> indexStats) {
        checkMonitoringElement(indexStats);
        checkSourceNode(version, indexStats);
        Map<?, ?> stats = (Map<?, ?>) indexStats.get("index_stats");
        assertThat(stats, hasKey("index"));
        Map<?, ?> total = (Map<?, ?>) stats.get("total");
        Map<?, ?> docs = (Map<?, ?>) total.get("docs");
        // These might have been taken before all the documents were added so we can't assert a whole lot about the number
        assertThat((Integer) docs.get("count"), greaterThanOrEqualTo(0));
    }

    private void checkShards(final Version version, Map<String, Object> shards) {
        checkMonitoringElement(shards);
        Map<?, ?> shard = (Map<?, ?>) shards.get("shard");
        assertThat(shard, allOf(hasKey("index"), hasKey("state"), hasKey("primary"), hasKey("node")));
    }

    private void checkIndicesStats(final Version version, Map<String, Object> indicesStats) {
        checkMonitoringElement(indicesStats);
        checkSourceNode(version, indicesStats);
        Map<?, ?> stats = (Map<?, ?>) indicesStats.get("indices_stats");
        Map<?, ?> all = (Map<?, ?>) stats.get("_all");
        Map<?, ?> primaries = (Map<?, ?>) all.get("primaries");
        Map<?, ?> docs = (Map<?, ?>) primaries.get("docs");
        // These might have been taken before all the documents were added so we can't assert a whole lot about the number
        assertThat((Integer) docs.get("count"), greaterThanOrEqualTo(0));
    }

    @SuppressWarnings("unchecked")
    private void checkNodeStats(final Version version, final String masterNodeId, Map<String, Object> nodeStats) {
        checkMonitoringElement(nodeStats);
        checkSourceNode(version, nodeStats);
        Map<?, ?> stats = (Map<?, ?>) nodeStats.get("node_stats");

        // Those fields are expected in every node stats documents
        Set<String> mandatoryKeys = new HashSet();
        mandatoryKeys.add("node_id");
        mandatoryKeys.add("node_master");
        mandatoryKeys.add("mlockall");
        mandatoryKeys.add("indices");
        mandatoryKeys.add("os");
        mandatoryKeys.add("fs");
        mandatoryKeys.add("process");
        mandatoryKeys.add("jvm");
        mandatoryKeys.add("thread_pool");

        // disk_threshold_* fields have been removed in 5.0 alpha5, we only check for them if the
        // current tested version is less than or equal to alpha4. Also, the current master node
        // might have collected its own node stats through the Monitoring plugin, and since it is
        // running under Version.CURRENT there's no chance to find these fields.
        if (version.onOrBefore(Version.V_5_0_0_alpha4)) {
            if (masterNodeId.equals((String) stats.get("node_id")) == false) {
                mandatoryKeys.add("disk_threshold_enabled");
                mandatoryKeys.add("disk_threshold_watermark_high");
            }
        }

        for (String key : mandatoryKeys) {
            assertThat("Expecting [" + key + "] to be present for bwc index in version [" + version + "]", stats, hasKey(key));
        }

        Set<String> keys = new HashSet(stats.keySet());
        keys.removeAll(mandatoryKeys);
        assertTrue("Found unexpected fields [" + Strings.collectionToCommaDelimitedString(keys) + "] " +
                "for bwc index in version [" + version + "]", keys.isEmpty());
    }

    private void checkClusterState(final Version version, Map<String, Object> clusterState) {
        checkMonitoringElement(clusterState);
        checkSourceNode(version, clusterState);
        Map<?, ?> stats = (Map<?, ?>) clusterState.get("cluster_state");
        assertThat(stats, allOf(hasKey("status"), hasKey("version"), hasKey("state_uuid"), hasKey("master_node"), hasKey("nodes")));
    }

    private void checkMonitoringElement(Map<String, Object> element) {
        assertThat(element, allOf(hasKey("cluster_uuid"), hasKey("timestamp")));
    }

    private void checkSourceNode(final Version version, Map<String, Object> element) {
        if (version.onOrAfter(Version.V_2_3_0)) {
            // The source_node field has been added in v2.3.0
            assertThat(element, hasKey("source_node"));
        }
    }
}
