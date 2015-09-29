/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.collector.shards;

import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.marvel.agent.collector.AbstractCollector;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.license.LicenseService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Collector for shards.
 * <p>
 * This collector runs on the master node only and collects the {@link ShardMarvelDoc} documents
 * for every index shard.
 */
public class ShardsCollector extends AbstractCollector<ShardsCollector> {

    public static final String NAME = "shards-collector";
    public static final String TYPE = "shards";

    @Inject
    public ShardsCollector(Settings settings, ClusterService clusterService, MarvelSettings marvelSettings, LicenseService licenseService) {
        super(settings, NAME, clusterService, marvelSettings, licenseService);
    }

    @Override
    protected boolean shouldCollect() {
        return super.shouldCollect() && isLocalNodeMaster();
    }

    @Override
    protected Collection<MarvelDoc> doCollect() throws Exception {
        List<MarvelDoc> results = new ArrayList<>(1);

        ClusterState clusterState = clusterService.state();
        if (clusterState != null) {
            RoutingTable routingTable = clusterState.routingTable();
            if (routingTable != null) {
                List<ShardRouting> shards = routingTable.allShards();
                if (shards != null) {
                    String clusterUUID = clusterUUID();
                    String stateUUID = clusterState.stateUUID();
                    long timestamp = System.currentTimeMillis();

                    for (ShardRouting shard : shards) {
                        if (match(shard.getIndex())) {
                            results.add(new ShardMarvelDoc(null, TYPE, id(stateUUID, shard), clusterUUID, timestamp, shard, stateUUID));
                        }
                    }
                }
            }
        }

        return Collections.unmodifiableCollection(results);
    }

    private boolean match(String indexName) {
        String[] indices = marvelSettings.indices();
        return CollectionUtils.isEmpty(indices) || Regex.simpleMatch(indices, indexName);
    }

    /**
     * Compute an id that has the format:
     *
     * {state_uuid}:{node_id || '_na'}:{index}:{shard}:{'p' || 'r'}
     */
    static String id(String stateUUID, ShardRouting shardRouting) {
        StringBuilder builder = new StringBuilder();
        builder.append(stateUUID);
        builder.append(':');
        if (shardRouting.assignedToNode()) {
            builder.append(shardRouting.currentNodeId());
        } else {
            builder.append("_na");
        }
        builder.append(':');
        builder.append(shardRouting.index());
        builder.append(':');
        builder.append(Integer.valueOf(shardRouting.id()));
        builder.append(':');
        if (shardRouting.primary()) {
            builder.append("p");
        } else {
            builder.append("r");
        }
        return builder.toString();
    }
}
