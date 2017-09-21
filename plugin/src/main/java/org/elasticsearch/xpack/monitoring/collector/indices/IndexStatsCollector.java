/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.collector.indices;

import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.monitoring.collector.Collector;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringDoc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Collector for indices and singular index statistics.
 * <p>
 * This collector runs on the master node only and collect a single {@link IndicesStatsMonitoringDoc} for the cluster and a
 * {@link IndexStatsMonitoringDoc} document for each existing index in the cluster.
 */
public class IndexStatsCollector extends Collector {

    private final Client client;

    public IndexStatsCollector(final Settings settings,
                               final ClusterService clusterService,
                               final MonitoringSettings monitoringSettings,
                               final XPackLicenseState licenseState,
                               final Client client) {
        super(settings, "index-stats", clusterService, monitoringSettings, licenseState);
        this.client = client;
    }

    @Override
    protected boolean shouldCollect() {
        return super.shouldCollect() && isLocalNodeMaster();
    }

    @Override
    protected Collection<MonitoringDoc> doCollect(final MonitoringDoc.Node node) throws Exception {
        final List<MonitoringDoc> results = new ArrayList<>();
        final IndicesStatsResponse indicesStats = client.admin().indices().prepareStats()
                .setIndices(monitoringSettings.indices())
                .setIndicesOptions(IndicesOptions.lenientExpandOpen())
                .clear()
                .setDocs(true)
                .setFieldData(true)
                .setIndexing(true)
                .setMerge(true)
                .setSearch(true)
                .setSegments(true)
                .setStore(true)
                .setRefresh(true)
                .setQueryCache(true)
                .setRequestCache(true)
                .get(monitoringSettings.indexStatsTimeout());

        final long timestamp = timestamp();
        final String clusterUuid = clusterUUID();

        // add the indices stats that we use to collect the index stats
        results.add(new IndicesStatsMonitoringDoc(clusterUuid, timestamp, node, indicesStats));

        // collect each index stats document
        for (IndexStats indexStats : indicesStats.getIndices().values()) {
            results.add(new IndexStatsMonitoringDoc(clusterUuid, timestamp, node, indexStats));
        }

        return Collections.unmodifiableCollection(results);
    }
}
