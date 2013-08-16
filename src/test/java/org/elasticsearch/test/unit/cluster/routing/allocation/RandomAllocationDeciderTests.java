/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.unit.cluster.routing.allocation;

import org.elasticsearch.cluster.ClusterInfoService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.MetaData.Builder;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.allocator.ShardsAllocators;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.cluster.routing.allocation.decider.SameShardAllocationDecider;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.test.integration.ElasticsearchTestCase;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;

import static org.elasticsearch.cluster.ClusterState.newClusterStateBuilder;
import static org.elasticsearch.cluster.metadata.IndexMetaData.newIndexMetaDataBuilder;
import static org.elasticsearch.cluster.metadata.MetaData.newMetaDataBuilder;
import static org.elasticsearch.cluster.node.DiscoveryNodes.newNodesBuilder;
import static org.elasticsearch.cluster.routing.RoutingBuilders.routingTable;
import static org.elasticsearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.test.unit.cluster.routing.allocation.RoutingAllocationTests.newNode;
import static org.hamcrest.Matchers.equalTo;

public class RandomAllocationDeciderTests extends ElasticsearchTestCase {

    /* This test will make random allocation decision on a growing and shrinking
     * cluster leading to a random distribution of the shards. After a certain
     * amount of iterations the test allows allocation unless the same shard is
     * already allocated on a node and balances the cluster to gain optimal
     * balance.*/
    @Test
    public void testRandomDecicions() {
        RandomAllocationDecider randomAllocationDecider = new RandomAllocationDecider(getRandom());
        AllocationService strategy = new AllocationService(settingsBuilder().build(), new AllocationDeciders(ImmutableSettings.EMPTY,
                new HashSet<AllocationDecider>(Arrays.asList(new SameShardAllocationDecider(ImmutableSettings.EMPTY),
                        randomAllocationDecider))), new ShardsAllocators(), ClusterInfoService.EMPTY);
        int indices = between(1, 20);
        Builder metaBuilder = newMetaDataBuilder();
        int maxNumReplicas = 1;
        int totalNumShards = 0;
        for (int i = 0; i < indices; i++) {
            int replicas = between(0, 6);
            maxNumReplicas = Math.max(maxNumReplicas, replicas+1);
            int numShards = between(1, 20);
            totalNumShards += numShards * (replicas+1);
            metaBuilder.put(newIndexMetaDataBuilder("INDEX_" + i).numberOfShards(numShards).numberOfReplicas(replicas));

        }
        MetaData metaData = metaBuilder.build();
        RoutingTable.Builder routingTableBuilder = routingTable();
        for (int i = 0; i < indices; i++) {
            routingTableBuilder.addAsNew(metaData.index("INDEX_" + i));
        }

        RoutingTable routingTable = routingTableBuilder.build();
        ClusterState clusterState = newClusterStateBuilder().metaData(metaData).routingTable(routingTable).build();
        int numIters = atLeast(20);
        int nodeIdCounter = 0;
        int atMostNodes = between(Math.max(1, maxNumReplicas), numIters);
        final boolean frequentNodes = randomBoolean();
        for (int i = 0; i < numIters; i++) {
            ClusterState.Builder stateBuilder = newClusterStateBuilder().state(clusterState);
            DiscoveryNodes.Builder newNodesBuilder = newNodesBuilder();
            newNodesBuilder.putAll(clusterState.nodes());

            if (clusterState.nodes().size() <= atMostNodes && 
                    (nodeIdCounter == 0 || (frequentNodes ? frequently() : rarely()))) {
                int numNodes = atLeast(1);
                for (int j = 0; j < numNodes; j++) {
                    logger.info("adding node [{}]", nodeIdCounter);
                    newNodesBuilder.put(newNode("NODE_" + (nodeIdCounter++)));
                }
            }

            if (nodeIdCounter > 1 && rarely()) {
                int nodeId = between(0, nodeIdCounter - 2);
                logger.info("removing node [{}]", nodeId);
                newNodesBuilder.remove("NODE_" + nodeId);
            }

            stateBuilder.nodes(newNodesBuilder.build());
            clusterState = stateBuilder.build();
            routingTable = strategy.reroute(clusterState).routingTable();
            clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();
            if (clusterState.routingNodes().shardsWithState(INITIALIZING).size() > 0) {
                routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING))
                        .routingTable();
                clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();
            }
        }
        logger.info("Fill up nodes such that every shard can be allocated");
        if (clusterState.nodes().size() < maxNumReplicas) {
            ClusterState.Builder stateBuilder = newClusterStateBuilder().state(clusterState);
            DiscoveryNodes.Builder newNodesBuilder = newNodesBuilder();
            newNodesBuilder.putAll(clusterState.nodes());
            for (int j = 0; j < (maxNumReplicas-clusterState.nodes().size()); j++) {
                logger.info("adding node [{}]", nodeIdCounter);
                newNodesBuilder.put(newNode("NODE_" + (nodeIdCounter++)));
            }
            stateBuilder.nodes(newNodesBuilder.build());
            clusterState = stateBuilder.build();
        }
        

        randomAllocationDecider.allwaysSayYes = true;
        logger.info("now say YES to everything");
        int iterations = 0;
        do {
            iterations++;
            routingTable = strategy.reroute(clusterState).routingTable();
            clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();
            if (clusterState.routingNodes().shardsWithState(INITIALIZING).size() > 0) {
                routingTable = strategy.applyStartedShards(clusterState, clusterState.routingNodes().shardsWithState(INITIALIZING))
                        .routingTable();
                clusterState = newClusterStateBuilder().state(clusterState).routingTable(routingTable).build();
            }

        } while (clusterState.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size() != 0 || 
                clusterState.routingNodes().shardsWithState(ShardRoutingState.UNASSIGNED).size() != 0 && iterations < 200);
        logger.info("Done Balancing after [{}] iterations", iterations);
        // we stop after 200 iterations if it didn't stabelize by then something is likely to be wrong
        assertThat("max num iteration exceeded", iterations, Matchers.lessThan(200)); 
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.INITIALIZING).size(), equalTo(0));
        assertThat(clusterState.routingNodes().shardsWithState(ShardRoutingState.UNASSIGNED).size(), equalTo(0));
        int shards = clusterState.routingNodes().shardsWithState(ShardRoutingState.STARTED).size();
        assertThat(shards, equalTo(totalNumShards));
        final int numNodes = clusterState.nodes().size();
        final int upperBound = (int) Math.round(((shards / numNodes) * 1.10));
        final int lowerBound = (int) Math.round(((shards / numNodes) * 0.90));
        for (int i = 0; i < nodeIdCounter; i++) {
            if (clusterState.getRoutingNodes().node("NODE_" + i) == null) {
                continue;
            }
            assertThat(clusterState.getRoutingNodes().node("NODE_" + i).shards().size(), Matchers.anyOf(
                    Matchers.anyOf(equalTo((shards / numNodes) + 1), equalTo((shards / numNodes) - 1), equalTo((shards / numNodes))),
                    Matchers.allOf(Matchers.greaterThanOrEqualTo(lowerBound), Matchers.lessThanOrEqualTo(upperBound))));
        }
    }

    private static final class RandomAllocationDecider extends AllocationDecider {

        private final Random random;

        public RandomAllocationDecider(Random random) {
            super(ImmutableSettings.EMPTY);
            this.random = random;
        }

        public boolean allwaysSayYes = false;

        @Override
        public Decision canRebalance(ShardRouting shardRouting, RoutingAllocation allocation) {
            return getRandomDecision();
        }

        private Decision getRandomDecision() {
            if (allwaysSayYes) {
                return Decision.YES;
            }
            switch (random.nextInt(10)) {
            case 9:
            case 8:
            case 7:
            case 6:
            case 5:
                return Decision.NO;
            case 4:
                return Decision.THROTTLE;
            case 3:
            case 2:
            case 1:
                return Decision.YES;
            default:
                return Decision.ALWAYS;
            }
        }

        @Override
        public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
            return getRandomDecision();
        }

        @Override
        public Decision canRemain(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
            return getRandomDecision();
        }

    }

}
