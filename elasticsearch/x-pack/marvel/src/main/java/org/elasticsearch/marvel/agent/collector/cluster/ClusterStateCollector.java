/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.collector.cluster;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.marvel.agent.collector.AbstractCollector;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.license.MarvelLicensee;
import org.elasticsearch.shield.InternalClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Collector for cluster state.
 * <p>
 * This collector runs on the master node only and collects {@link ClusterStateMarvelDoc} document
 * at a given frequency.
 */
public class ClusterStateCollector extends AbstractCollector<ClusterStateCollector> {

    public static final String NAME = "cluster-state-collector";
    public static final String TYPE = "cluster_state";
    public static final String NODE_TYPE = "node";

    private final Client client;

    @Inject
    public ClusterStateCollector(Settings settings, ClusterService clusterService, DiscoveryService discoveryService,
                                 MarvelSettings marvelSettings, MarvelLicensee marvelLicensee, InternalClient client) {
        super(settings, NAME, clusterService,  discoveryService, marvelSettings, marvelLicensee);
        this.client = client;
    }

    @Override
    protected boolean shouldCollect() {
        return super.shouldCollect() && isLocalNodeMaster();
    }

    @Override
    protected Collection<MarvelDoc> doCollect() throws Exception {
        List<MarvelDoc> results = new ArrayList<>(3);

        ClusterState clusterState = clusterService.state();
        String clusterUUID = clusterState.metaData().clusterUUID();
        String stateUUID = clusterState.stateUUID();
        long timestamp = System.currentTimeMillis();
        DiscoveryNode sourceNode = localNode();

        ClusterHealthResponse clusterHealth = client.admin().cluster().prepareHealth().get(marvelSettings.clusterStateTimeout());

        // Adds a cluster_state document with associated status
        ClusterStateMarvelDoc clusterStateDoc = new ClusterStateMarvelDoc();
        clusterStateDoc.setClusterUUID(clusterUUID);
        clusterStateDoc.setType(TYPE);
        clusterStateDoc.setTimestamp(timestamp);
        clusterStateDoc.setSourceNode(sourceNode);
        clusterStateDoc.setClusterState(clusterState);
        clusterStateDoc.setStatus(clusterHealth.getStatus());
        results.add(clusterStateDoc);

        DiscoveryNodes nodes = clusterState.nodes();
        if (nodes != null) {
            for (DiscoveryNode node : nodes) {
                // Adds a document for every node in the marvel timestamped index (type "nodes")
                ClusterStateNodeMarvelDoc clusterStateNodeDoc = new ClusterStateNodeMarvelDoc();
                clusterStateNodeDoc.setClusterUUID(clusterUUID);;
                clusterStateNodeDoc.setType(NODE_TYPE);
                clusterStateNodeDoc.setTimestamp(timestamp);
                clusterStateNodeDoc.setSourceNode(sourceNode);
                clusterStateNodeDoc.setStateUUID(stateUUID);
                clusterStateNodeDoc.setNodeId(node.getId());
                results.add(clusterStateNodeDoc);

                // Adds a document for every node in the marvel data index (type "node")
                DiscoveryNodeMarvelDoc discoveryNodeDoc = new DiscoveryNodeMarvelDoc(dataIndexNameResolver.resolve(timestamp), NODE_TYPE,
                        node.getId());
                discoveryNodeDoc.setClusterUUID(clusterUUID);
                discoveryNodeDoc.setTimestamp(timestamp);
                discoveryNodeDoc.setSourceNode(node);
                discoveryNodeDoc.setNode(node);
                results.add(discoveryNodeDoc);
            }
        }

        return Collections.unmodifiableCollection(results);
    }
}
