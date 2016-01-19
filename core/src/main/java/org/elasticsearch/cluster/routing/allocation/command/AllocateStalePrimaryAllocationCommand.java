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

import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.RerouteExplanation;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.decider.Decision;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardNotFoundException;

import java.io.IOException;

/**
 * Allocates an unassigned stale primary shard to a specific node. Use with extreme care as this will result in data loss.
 * Allocation deciders are ignored.
 */
public class AllocateStalePrimaryAllocationCommand extends BasePrimaryAllocationCommand {
    public static final String NAME = "allocate_stale_primary";

    private static final ObjectParser<Builder, Void> STALE_PRIMARY_PARSER = BasePrimaryAllocationCommand.createAllocatePrimaryParser(NAME);

    /**
     * Creates a new {@link AllocateStalePrimaryAllocationCommand}
     *
     * @param shardId        {@link ShardId} of the shard to assign
     * @param node           node id of the node to assign the shard to
     * @param acceptDataLoss whether the user agrees to data loss
     */
    public AllocateStalePrimaryAllocationCommand(ShardId shardId, String node, boolean acceptDataLoss) {
        super(shardId, node, acceptDataLoss);
    }

    @Override
    public String name() {
        return NAME;
    }

    public static class Builder extends BasePrimaryAllocationCommand.Builder<AllocateStalePrimaryAllocationCommand> {

        @Override
        public Builder parse(XContentParser parser) throws IOException {
            return STALE_PRIMARY_PARSER.parse(parser, this);
        }

        @Override
        public AllocateStalePrimaryAllocationCommand build() {
            validate();
            return new AllocateStalePrimaryAllocationCommand(new ShardId(index, shard), node, acceptDataLoss);
        }
    }

    public static class Factory extends AbstractAllocateAllocationCommand.Factory<AllocateStalePrimaryAllocationCommand> {

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

        final ShardRouting shardRouting;
        try {
            shardRouting = allocation.routingTable().shardRoutingTable(shardId).primaryShard();
        } catch (IndexNotFoundException | ShardNotFoundException e) {
            return explainOrThrowRejectedCommand(explain, allocation, e);
        }
        if (shardRouting.unassigned() == false) {
            return explainOrThrowRejectedCommand(explain, allocation, "primary " + shardId + " is already assigned");
        }

        if (acceptDataLoss == false) {
            return explainOrThrowRejectedCommand(explain, allocation,
                "allocating an empty primary for " + shardId + " can result in data loss. Please confirm by setting the accept_data_loss parameter to true");
        }

        final IndexMetaData indexMetaData = allocation.metaData().index(shardRouting.getIndex());
        if (shardRouting.allocatedPostIndexCreate(indexMetaData) == false) {
            return explainOrThrowRejectedCommand(explain, allocation,
                "trying to allocate an existing primary shard " + shardId + ", while no such shard has ever been active");
        }

        initializeUnassignedShard(allocation, routingNodes, routingNode, shardRouting);
        return new RerouteExplanation(this, allocation.decision(Decision.YES, name() + " (allocation command)", "ignore deciders"));
    }

}
