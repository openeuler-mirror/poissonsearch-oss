/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.collector.node;

import org.elasticsearch.bootstrap.BootstrapInfo;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.routing.allocation.decider.DiskThresholdDecider;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.marvel.agent.collector.AbstractCollectorTestCase;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.license.MarvelLicensee;
import org.elasticsearch.node.service.NodeService;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;

import java.util.Collection;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;

// numClientNodes is set to 0 in this test because the NodeStatsCollector never collects data on client nodes:
// the NodeStatsCollector.shouldCollect() method checks if the node has node files and client nodes don't have
// such files.
@ClusterScope(numClientNodes = 0)
public class NodeStatsCollectorTests extends AbstractCollectorTestCase {
    public void testNodeStatsCollector() throws Exception {
        String[] nodes = internalCluster().getNodeNames();
        for (String node : nodes) {
            logger.info("--> collecting node stats on node [{}]", node);
            Collection<MarvelDoc> results = newNodeStatsCollector(node).doCollect();
            assertThat(results, hasSize(1));

            MarvelDoc marvelDoc = results.iterator().next();
            assertNotNull(marvelDoc);
            assertThat(marvelDoc, instanceOf(NodeStatsMarvelDoc.class));

            NodeStatsMarvelDoc nodeStatsMarvelDoc = (NodeStatsMarvelDoc) marvelDoc;
            assertThat(nodeStatsMarvelDoc.clusterUUID(), equalTo(client().admin().cluster().prepareState().setMetaData(true).get().getState().metaData().clusterUUID()));
            assertThat(nodeStatsMarvelDoc.timestamp(), greaterThan(0L));
            assertThat(nodeStatsMarvelDoc.type(), equalTo(NodeStatsCollector.TYPE));

            assertThat(nodeStatsMarvelDoc.getNodeId(), equalTo(internalCluster().getInstance(DiscoveryService.class, node).localNode().id()));
            assertThat(nodeStatsMarvelDoc.isNodeMaster(), equalTo(node.equals(internalCluster().getMasterName())));
            assertThat(nodeStatsMarvelDoc.isMlockall(), equalTo(BootstrapInfo.isMemoryLocked()));
            assertNotNull(nodeStatsMarvelDoc.isDiskThresholdDeciderEnabled());
            assertNotNull(nodeStatsMarvelDoc.getDiskThresholdWaterMarkHigh());

            assertNotNull(nodeStatsMarvelDoc.getNodeStats());
        }
    }

    public void testNodeStatsCollectorWithLicensing() {
        try {
            String[] nodes = internalCluster().getNodeNames();
            for (String node : nodes) {
                logger.debug("--> creating a new instance of the collector");
                NodeStatsCollector collector = newNodeStatsCollector(node);
                assertNotNull(collector);

                logger.debug("--> enabling license and checks that the collector can collect data");
                enableLicense();
                assertCanCollect(collector);

                logger.debug("--> starting graceful period and checks that the collector can still collect data");
                beginGracefulPeriod();
                assertCanCollect(collector);

                logger.debug("--> ending graceful period and checks that the collector cannot collect data");
                endGracefulPeriod();
                assertCannotCollect(collector);

                logger.debug("--> disabling license and checks that the collector cannot collect data");
                disableLicense();
                assertCannotCollect(collector);
            }
        } finally {
            // Ensure license is enabled before finishing the test
            enableLicense();
        }
    }

    private NodeStatsCollector newNodeStatsCollector(final String nodeId) {
        return new NodeStatsCollector(internalCluster().getInstance(Settings.class, nodeId),
                internalCluster().getInstance(ClusterService.class, nodeId),
                internalCluster().getInstance(MarvelSettings.class, nodeId),
                internalCluster().getInstance(MarvelLicensee.class, nodeId),
                internalCluster().getInstance(NodeService.class, nodeId),
                internalCluster().getInstance(DiscoveryService.class, nodeId),
                internalCluster().getInstance(NodeEnvironment.class, nodeId),
                internalCluster().getInstance(DiskThresholdDecider.class, nodeId));
    }
}
