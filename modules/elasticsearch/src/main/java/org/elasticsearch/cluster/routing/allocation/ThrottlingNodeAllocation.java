/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
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

package org.elasticsearch.cluster.routing.allocation;

import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.routing.MutableShardRouting;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.node.settings.NodeSettingsService;

/**
 * @author kimchy (shay.banon)
 */
public class ThrottlingNodeAllocation extends NodeAllocation {

    static {
        MetaData.addDynamicSettings(
                "cluster.routing.allocation.node_initial_primaries_recoveries",
                "cluster.routing.allocation.node_concurrent_recoveries"
        );
    }

    private volatile int primariesInitialRecoveries;
    private volatile int concurrentRecoveries;

    @Inject public ThrottlingNodeAllocation(Settings settings, NodeSettingsService nodeSettingsService) {
        super(settings);

        this.primariesInitialRecoveries = componentSettings.getAsInt("node_initial_primaries_recoveries", 4);
        this.concurrentRecoveries = componentSettings.getAsInt("concurrent_recoveries", componentSettings.getAsInt("node_concurrent_recoveries", 2));
        logger.debug("using node_concurrent_recoveries [{}], node_initial_primaries_recoveries [{}]", concurrentRecoveries, primariesInitialRecoveries);

        nodeSettingsService.addListener(new ApplySettings());
    }

    @Override public Decision canAllocate(ShardRouting shardRouting, RoutingNode node, RoutingAllocation allocation) {
        if (shardRouting.primary()) {
            boolean primaryUnassigned = false;
            for (MutableShardRouting shard : allocation.routingNodes().unassigned()) {
                if (shard.shardId().equals(shardRouting.shardId())) {
                    primaryUnassigned = true;
                }
            }
            if (primaryUnassigned) {
                // primary is unassigned, means we are going to do recovery from gateway
                // count *just the primary* currently doing recovery on the node and check against concurrent_recoveries
                int primariesInRecovery = 0;
                for (MutableShardRouting shard : node) {
                    if (shard.state() == ShardRoutingState.INITIALIZING && shard.primary()) {
                        primariesInRecovery++;
                    }
                }
                if (primariesInRecovery >= primariesInitialRecoveries) {
                    return Decision.THROTTLE;
                } else {
                    return Decision.YES;
                }
            }
        }

        // either primary or replica doing recovery (from peer shard)

        // count the number of recoveries on the node, its for both target (INITIALIZING) and source (RELOCATING)
        int currentRecoveries = 0;
        for (MutableShardRouting shard : node) {
            if (shard.state() == ShardRoutingState.INITIALIZING || shard.state() == ShardRoutingState.RELOCATING) {
                currentRecoveries++;
            }
        }

        if (currentRecoveries >= concurrentRecoveries) {
            return Decision.THROTTLE;
        } else {
            return Decision.YES;
        }
    }

    class ApplySettings implements NodeSettingsService.Listener {
        @Override public void onRefreshSettings(Settings settings) {
            int primariesInitialRecoveries = settings.getAsInt("cluster.routing.allocation.node_initial_primaries_recoveries", ThrottlingNodeAllocation.this.primariesInitialRecoveries);
            if (primariesInitialRecoveries != ThrottlingNodeAllocation.this.primariesInitialRecoveries) {
                logger.info("updating [cluster.routing.allocation.node_initial_primaries_recoveries] from [{}] to [{}]", ThrottlingNodeAllocation.this.primariesInitialRecoveries, primariesInitialRecoveries);
                ThrottlingNodeAllocation.this.primariesInitialRecoveries = primariesInitialRecoveries;
            }

            int concurrentRecoveries = settings.getAsInt("cluster.routing.allocation.node_concurrent_recoveries", ThrottlingNodeAllocation.this.concurrentRecoveries);
            if (concurrentRecoveries != ThrottlingNodeAllocation.this.concurrentRecoveries) {
                logger.info("updating [cluster.routing.allocation.node_concurrent_recoveries] from [{}] to [{}]", ThrottlingNodeAllocation.this.concurrentRecoveries, concurrentRecoveries);
                ThrottlingNodeAllocation.this.concurrentRecoveries = concurrentRecoveries;
            }
        }
    }
}
