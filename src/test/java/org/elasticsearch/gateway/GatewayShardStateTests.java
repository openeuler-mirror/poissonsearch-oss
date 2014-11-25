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
package org.elasticsearch.gateway;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ElasticsearchTestCase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


public class GatewayShardStateTests extends ElasticsearchTestCase {

    private final Settings IDX_SETTINGS = ImmutableSettings.EMPTY;

    public void testWriteShardState() throws Exception {
        try (NodeEnvironment env = newNodeEnvironment()) {
            GatewayShardsState state = new GatewayShardsState(ImmutableSettings.EMPTY, env, null);
            ShardId id = new ShardId("foo", 1);
            long version = between(1, Integer.MAX_VALUE / 2);
            boolean primary = randomBoolean();
            ShardStateInfo state1 = new ShardStateInfo(version, primary);
            state.maybeWriteShardState(id, state1, null, IDX_SETTINGS);
            ShardStateInfo shardStateInfo = state.loadShardInfo(id, IDX_SETTINGS);
            assertEquals(shardStateInfo, state1);

            ShardStateInfo state2 = new ShardStateInfo(version, primary);
            state.maybeWriteShardState(id, state2, state1, IDX_SETTINGS);
            shardStateInfo = state.loadShardInfo(id, IDX_SETTINGS);
            assertEquals(shardStateInfo, state1);

            ShardStateInfo state3 = new ShardStateInfo(version + 1, primary);
            state.maybeWriteShardState(id, state3, state1, IDX_SETTINGS);
            shardStateInfo = state.loadShardInfo(id, IDX_SETTINGS);
            assertEquals(shardStateInfo, state3);
            assertTrue(state.getCurrentState().isEmpty());
        }
    }

    public void testPersistRoutingNode() throws Exception {
        MetaData metaData = MetaData.builder().put(IndexMetaData.builder("idx")
                .settings(settings(Version.CURRENT)
                                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1)
                                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
                )).generateUuidIfNeeded().build();
        ClusterState clusterState = new ClusterState(new ClusterName("elasticsearch"), 1, metaData, null, null, null, null);

        try (NodeEnvironment env = newNodeEnvironment()) {
            GatewayShardsState state = new GatewayShardsState(ImmutableSettings.EMPTY, env, null);
            int numShards = between(0, 100);
            List<MutableShardRouting> shards = new ArrayList<>();
            List<MutableShardRouting> active = new ArrayList<>();
            for (int i = 0; i < numShards; i++) {
                long version = between(1, Integer.MAX_VALUE / 2);
                ShardRoutingState shardRoutingState = randomFrom(ShardRoutingState.INITIALIZING, ShardRoutingState.RELOCATING, ShardRoutingState.STARTED);
                MutableShardRouting mutableShardRouting = new MutableShardRouting("idx", i, "foo", randomBoolean(), shardRoutingState, version);
                if (mutableShardRouting.active()) {
                    active.add(mutableShardRouting);
                }
                shards.add(mutableShardRouting);
            }
            RoutingNode node = new RoutingNode("foo", new DiscoveryNode("foo", null, Version.CURRENT), shards);

            Map<ShardId, ShardStateInfo> shardIdShardStateInfoMap = state.persistRoutingNodeState(node, clusterState);
            assertEquals(shardIdShardStateInfoMap.size(), active.size());
            for (Map.Entry<ShardId, ShardStateInfo> written : shardIdShardStateInfoMap.entrySet()) {
                ShardStateInfo shardStateInfo = state.loadShardInfo(written.getKey(), IDX_SETTINGS);
                assertEquals(shardStateInfo, written.getValue());
                if (randomBoolean()) {
                    assertNull(state.loadShardInfo(new ShardId("no_such_index", written.getKey().id()), IDX_SETTINGS));
                }
            }
            assertTrue(state.getCurrentState().isEmpty());

            state.getCurrentState().putAll(shardIdShardStateInfoMap);

            if (randomBoolean()) { // sometimes write the same thing twice
                shardIdShardStateInfoMap = state.persistRoutingNodeState(node, clusterState);
                assertEquals(shardIdShardStateInfoMap.size(), active.size());
                for (Map.Entry<ShardId, ShardStateInfo> written : shardIdShardStateInfoMap.entrySet()) {
                    ShardStateInfo shardStateInfo = state.loadShardInfo(written.getKey(), IDX_SETTINGS);
                    assertEquals(shardStateInfo, written.getValue());
                    if (randomBoolean()) {
                        assertNull(state.loadShardInfo(new ShardId("no_such_index", written.getKey().id()), IDX_SETTINGS));
                    }
                }
            }

            List<MutableShardRouting> nextRoundOfShards = new ArrayList<>();

            for (MutableShardRouting routing : shards) {
                nextRoundOfShards.add(new MutableShardRouting(routing, routing.version() + 1));
            }
            node = new RoutingNode("foo", new DiscoveryNode("foo", null, Version.CURRENT), nextRoundOfShards);
            Map<ShardId, ShardStateInfo> shardIdShardStateInfoMapNew = state.persistRoutingNodeState(node, clusterState);
            assertEquals(shardIdShardStateInfoMapNew.size(), active.size());
            for (Map.Entry<ShardId, ShardStateInfo> written : shardIdShardStateInfoMapNew.entrySet()) {
                ShardStateInfo shardStateInfo = state.loadShardInfo(written.getKey(), IDX_SETTINGS);
                assertEquals(shardStateInfo, written.getValue());
                ShardStateInfo oldStateInfo = shardIdShardStateInfoMap.get(written.getKey());
                assertEquals(oldStateInfo.version, written.getValue().version - 1);
                if (randomBoolean()) {
                    assertNull(state.loadShardInfo(new ShardId("no_such_index", written.getKey().id()), IDX_SETTINGS));
                }
            }
        }
    }
}
