/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.xpack.ccr.allocation;

import com.carrotsearch.hppc.IntHashSet;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterInfo;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ESAllocationTestCase;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodeRole;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.RecoverySource;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDecider;
import org.elasticsearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.repositories.IndexId;
import org.elasticsearch.snapshots.Snapshot;
import org.elasticsearch.snapshots.SnapshotId;
import org.elasticsearch.test.VersionUtils;
import org.elasticsearch.xpack.ccr.CcrSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Collections.emptyMap;
import static org.elasticsearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.hamcrest.Matchers.equalTo;

public class CcrPrimaryFollowerAllocationDeciderTests extends ESAllocationTestCase {

    public void testRegularIndex() {
        String index = "test-index";
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder(index).settings(settings(Version.CURRENT))
            .numberOfShards(1).numberOfReplicas(1);
        List<DiscoveryNode> nodes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Set<DiscoveryNodeRole> roles = new HashSet<>();
            roles.add(DiscoveryNodeRole.DATA_ROLE);
            if (randomBoolean()) {
                roles.add(DiscoveryNodeRole.REMOTE_CLUSTER_CLIENT_ROLE);
            }
            nodes.add(newNode("node" + i, roles));
        }
        DiscoveryNodes.Builder discoveryNodes = DiscoveryNodes.builder();
        nodes.forEach(discoveryNodes::add);
        Metadata metadata = Metadata.builder().put(indexMetadata).build();
        RoutingTable.Builder routingTable = RoutingTable.builder();
        if (randomBoolean()) {
            routingTable.addAsNew(metadata.index(index));
        } else if (randomBoolean()) {
            routingTable.addAsRecovery(metadata.index(index));
        } else if (randomBoolean()) {
            routingTable.addAsNewRestore(metadata.index(index), newSnapshotRecoverySource(), new IntHashSet());
        } else {
            routingTable.addAsRestore(metadata.index(index), newSnapshotRecoverySource());
        }
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .nodes(DiscoveryNodes.EMPTY_NODES).metadata(metadata).routingTable(routingTable.build()).build();
        for (int i = 0; i < clusterState.routingTable().index(index).shards().size(); i++) {
            IndexShardRoutingTable shardRouting = clusterState.routingTable().index(index).shard(i);
            assertThat(shardRouting.size(), equalTo(2));
            assertThat(shardRouting.primaryShard().state(), equalTo(UNASSIGNED));
            Decision decision = executeAllocation(clusterState, shardRouting.primaryShard(), randomFrom(nodes));
            assertThat(decision.type(), equalTo(Decision.Type.YES));
            assertThat(decision.getExplanation(), equalTo("shard is not a follower and is not under the purview of this decider"));
            for (ShardRouting replica : shardRouting.replicaShards()) {
                assertThat(replica.state(), equalTo(UNASSIGNED));
                decision = executeAllocation(clusterState, replica, randomFrom(nodes));
                assertThat(decision.type(), equalTo(Decision.Type.YES));
                assertThat(decision.getExplanation(), equalTo("shard is not a follower and is not under the purview of this decider"));
            }
        }
    }

    public void testAlreadyBootstrappedFollowerIndex() {
        String index = "test-index";
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder(index)
            .settings(settings(Version.CURRENT).put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true))
            .numberOfShards(1).numberOfReplicas(1);
        List<DiscoveryNode> nodes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            final Set<DiscoveryNodeRole> roles = new HashSet<>();
            roles.add(DiscoveryNodeRole.DATA_ROLE);
            if (randomBoolean()) {
                roles.add(DiscoveryNodeRole.REMOTE_CLUSTER_CLIENT_ROLE);
            }
            nodes.add(newNode("node" + i, roles));
        }
        DiscoveryNodes.Builder discoveryNodes = DiscoveryNodes.builder();
        nodes.forEach(discoveryNodes::add);
        Metadata metadata = Metadata.builder().put(indexMetadata).build();
        RoutingTable.Builder routingTable = RoutingTable.builder().addAsRecovery(metadata.index(index));
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .nodes(discoveryNodes).metadata(metadata).routingTable(routingTable.build()).build();
        for (int i = 0; i < clusterState.routingTable().index(index).shards().size(); i++) {
            IndexShardRoutingTable shardRouting = clusterState.routingTable().index(index).shard(i);
            assertThat(shardRouting.size(), equalTo(2));
            assertThat(shardRouting.primaryShard().state(), equalTo(UNASSIGNED));
            Decision decision = executeAllocation(clusterState, shardRouting.primaryShard(), randomFrom(nodes));
            assertThat(decision.type(), equalTo(Decision.Type.YES));
            assertThat(decision.getExplanation(),
                equalTo("shard is a primary follower but was bootstrapped already; hence is not under the purview of this decider"));
            for (ShardRouting replica : shardRouting.replicaShards()) {
                assertThat(replica.state(), equalTo(UNASSIGNED));
                decision = executeAllocation(clusterState, replica, randomFrom(nodes));
                assertThat(decision.type(), equalTo(Decision.Type.YES));
                assertThat(decision.getExplanation(), equalTo("shard is a replica follower and is not under the purview of this decider"));
            }
        }
    }

    public void testBootstrappingFollowerIndex() {
        String index = "test-index";
        IndexMetadata.Builder indexMetadata = IndexMetadata.builder(index)
            .settings(settings(Version.CURRENT).put(CcrSettings.CCR_FOLLOWING_INDEX_SETTING.getKey(), true))
            .numberOfShards(1).numberOfReplicas(1);
        final DiscoveryNode dataOnlyNode = newNode("data_role_only", Sets.newHashSet(DiscoveryNodeRole.DATA_ROLE));
        final DiscoveryNode dataAndRemoteNode = newNode("data_and_remote_cluster_client_role",
            Sets.newHashSet(DiscoveryNodeRole.DATA_ROLE, DiscoveryNodeRole.REMOTE_CLUSTER_CLIENT_ROLE));
        final DiscoveryNode nodeWithLegacyRolesOnly = newNodeWithLegacyRoles("legacy_roles_only");
        DiscoveryNodes discoveryNodes = DiscoveryNodes.builder().add(dataOnlyNode).add(dataAndRemoteNode).build();
        Metadata metadata = Metadata.builder().put(indexMetadata).build();
        RoutingTable.Builder routingTable = RoutingTable.builder()
            .addAsNewRestore(metadata.index(index), newSnapshotRecoverySource(), new IntHashSet());
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .nodes(discoveryNodes).metadata(metadata).routingTable(routingTable.build()).build();
        for (int i = 0; i < clusterState.routingTable().index(index).shards().size(); i++) {
            IndexShardRoutingTable shardRouting = clusterState.routingTable().index(index).shard(i);
            assertThat(shardRouting.size(), equalTo(2));
            assertThat(shardRouting.primaryShard().state(), equalTo(UNASSIGNED));
            Decision noDecision = executeAllocation(clusterState, shardRouting.primaryShard(), dataOnlyNode);
            assertThat(noDecision.type(), equalTo(Decision.Type.NO));
            assertThat(noDecision.getExplanation(),
                equalTo("shard is a primary follower and being bootstrapped, but node does not have the remote_cluster_client role"));
            Decision yesDecision = executeAllocation(clusterState, shardRouting.primaryShard(), dataAndRemoteNode);
            assertThat(yesDecision.type(), equalTo(Decision.Type.YES));
            assertThat(yesDecision.getExplanation(), equalTo("shard is a primary follower and node has the remote_cluster_client role"));

            yesDecision = executeAllocation(clusterState, shardRouting.primaryShard(), nodeWithLegacyRolesOnly);
            assertThat(yesDecision.type(), equalTo(Decision.Type.YES));
            assertThat(yesDecision.getExplanation(), equalTo("shard is a primary follower and node has only the legacy roles"));

            for (ShardRouting replica : shardRouting.replicaShards()) {
                assertThat(replica.state(), equalTo(UNASSIGNED));
                yesDecision = executeAllocation(clusterState, replica, randomFrom(dataOnlyNode, dataAndRemoteNode));
                assertThat(yesDecision.type(), equalTo(Decision.Type.YES));
                assertThat(yesDecision.getExplanation(),
                    equalTo("shard is a replica follower and is not under the purview of this decider"));
            }
        }
    }

    static DiscoveryNode newNodeWithLegacyRoles(String id) {
        final Version version = VersionUtils.randomVersionBetween(random(),
            Version.V_6_0_0, VersionUtils.getPreviousVersion(DiscoveryNodeRole.REMOTE_CLUSTER_CLIENT_ROLE_VERSION));
        return new DiscoveryNode(id, buildNewFakeTransportAddress(), emptyMap(), Sets.newHashSet(DiscoveryNodeRole.DATA_ROLE), version);
    }

    static Decision executeAllocation(ClusterState clusterState, ShardRouting shardRouting, DiscoveryNode node) {
        final AllocationDecider decider = new CcrPrimaryFollowerAllocationDecider();
        final RoutingAllocation routingAllocation = new RoutingAllocation(new AllocationDeciders(Collections.singletonList(decider)),
            new RoutingNodes(clusterState), clusterState, ClusterInfo.EMPTY, System.nanoTime());
        routingAllocation.debugDecision(true);
        return decider.canAllocate(shardRouting, new RoutingNode(node.getId(), node), routingAllocation);
    }

    static RecoverySource.SnapshotRecoverySource newSnapshotRecoverySource() {
        Snapshot snapshot = new Snapshot("repo", new SnapshotId("name", "_uuid"));
        return new RecoverySource.SnapshotRecoverySource(UUIDs.randomBase64UUID(), snapshot, Version.CURRENT,
            new IndexId("test", UUIDs.randomBase64UUID(random())));
    }
}
