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

package org.elasticsearch.cluster.routing.allocation.command;

import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.RerouteExplanation;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardNotFoundException;

import java.io.IOException;
import java.util.List;

/**
 * Allocates an unassigned replica shard to a specific node. Checks if allocation deciders allow allocation.
 */
public class AllocateReplicaAllocationCommand extends AbstractAllocateAllocationCommand {
    public static final String NAME = "allocate_replica";

    private static final ObjectParser<AllocateReplicaAllocationCommand.Builder, Void> REPLICA_PARSER = createAllocateParser(NAME);

    /**
     * Creates a new {@link AllocateReplicaAllocationCommand}
     *
     * @param shardId        {@link ShardId} of the shard to assign
     * @param node           node id of the node to assign the shard to
     */
    public AllocateReplicaAllocationCommand(ShardId shardId, String node) {
        super(shardId, node);
    }

    @Override
    public String name() {
        return NAME;
    }

    protected static class Builder extends AbstractAllocateAllocationCommand.Builder<AllocateReplicaAllocationCommand> {

        @Override
        public Builder parse(XContentParser parser) throws IOException {
            return REPLICA_PARSER.parse(parser, this);
        }

        @Override
        public AllocateReplicaAllocationCommand build() {
            validate();
            return new AllocateReplicaAllocationCommand(new ShardId(index, shard), node);
        }
    }

    public static class Factory extends AbstractAllocateAllocationCommand.Factory<AllocateReplicaAllocationCommand> {
        @Override
        protected Builder newBuilder() {
            return new Builder();
        }
    }

    @Override
    public RerouteExplanation execute(RoutingAllocation allocation, boolean explain) {
        final DiscoveryNode discoNode;
        try {
            discoNode = allocation.nodes().resolveNode(node);
        } catch (IllegalArgumentException e) {
            return explainOrThrowRejectedCommand(explain, allocation, e);
        }
        final RoutingNodes routingNodes = allocation.routingNodes();
        RoutingNode routingNode = routingNodes.node(discoNode.id());
        if (routingNode == null) {
            return explainOrThrowMissingRoutingNode(allocation, explain, discoNode);
        }

        final ShardRouting primaryShardRouting;
        try {
            primaryShardRouting = allocation.routingTable().shardRoutingTable(shardId).primaryShard();
        } catch (IndexNotFoundException | ShardNotFoundException e) {
            return explainOrThrowRejectedCommand(explain, allocation, e);
        }
        if (primaryShardRouting.unassigned()) {
            return explainOrThrowRejectedCommand(explain, allocation,
                "trying to allocate a replica shard " + shardId + ", while corresponding primary shard is still unassigned");
        }

        List<ShardRouting> replicaShardRoutings = allocation.routingTable().shardRoutingTable(shardId).replicaShardsWithState(ShardRoutingState.UNASSIGNED);
        ShardRouting shardRouting;
        if (replicaShardRoutings.isEmpty()) {
            return explainOrThrowRejectedCommand(explain, allocation,
                "all copies of " + shardId +" are already assigned. Use the move allocation command instead");
        } else {
            shardRouting = replicaShardRoutings.get(0);
        }

        Decision decision = allocation.deciders().canAllocate(shardRouting, routingNode, allocation);
        if (decision.type() == Decision.Type.NO) {
            // don't use explainOrThrowRejectedCommand to keep the original "NO" decision
            if (explain) {
                return new RerouteExplanation(this, decision);
            }
            throw new IllegalArgumentException("[" + name() + "] allocation of " + shardId + " on node " + discoNode + " is not allowed, reason: " + decision);
        }

        initializeUnassignedShard(allocation, routingNodes, routingNode, shardRouting);
        return new RerouteExplanation(this, decision);
    }


}
