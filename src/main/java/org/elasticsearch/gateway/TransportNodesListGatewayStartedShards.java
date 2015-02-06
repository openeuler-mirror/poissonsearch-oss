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

import com.google.common.collect.Lists;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.nodes.*;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 *
 */
public class TransportNodesListGatewayStartedShards extends TransportNodesOperationAction<TransportNodesListGatewayStartedShards.Request, TransportNodesListGatewayStartedShards.NodesGatewayStartedShards, TransportNodesListGatewayStartedShards.NodeRequest, TransportNodesListGatewayStartedShards.NodeGatewayStartedShards> {

    public static final String ACTION_NAME = "internal:gateway/local/started_shards";

    private GatewayShardsState shardsState;

    @Inject
    public TransportNodesListGatewayStartedShards(Settings settings, ClusterName clusterName, ThreadPool threadPool, ClusterService clusterService, TransportService transportService, ActionFilters actionFilters) {
        super(settings, ACTION_NAME, clusterName, threadPool, clusterService, transportService, actionFilters);
    }

    TransportNodesListGatewayStartedShards initGateway(GatewayShardsState shardsState) {
        this.shardsState = shardsState;
        return this;
    }

    public ActionFuture<NodesGatewayStartedShards> list(ShardId shardId, String[] nodesIds, @Nullable TimeValue timeout) {
        return execute(new Request(shardId, nodesIds).timeout(timeout));
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.GENERIC;
    }

    @Override
    protected boolean transportCompress() {
        return true; // this can become big...
    }

    @Override
    protected Request newRequest() {
        return new Request();
    }

    @Override
    protected NodeRequest newNodeRequest() {
        return new NodeRequest();
    }

    @Override
    protected NodeRequest newNodeRequest(String nodeId, Request request) {
        return new NodeRequest(nodeId, request);
    }

    @Override
    protected NodeGatewayStartedShards newNodeResponse() {
        return new NodeGatewayStartedShards();
    }

    @Override
    protected NodesGatewayStartedShards newResponse(Request request, AtomicReferenceArray responses) {
        final List<NodeGatewayStartedShards> nodesList = Lists.newArrayList();
        final List<FailedNodeException> failures = Lists.newArrayList();
        for (int i = 0; i < responses.length(); i++) {
            Object resp = responses.get(i);
            if (resp instanceof NodeGatewayStartedShards) { // will also filter out null response for unallocated ones
                nodesList.add((NodeGatewayStartedShards) resp);
            } else if (resp instanceof FailedNodeException) {
                failures.add((FailedNodeException) resp);
            } else {
                logger.warn("unknown response type [{}], expected NodeLocalGatewayStartedShards or FailedNodeException", resp);
            }
        }
        return new NodesGatewayStartedShards(clusterName, nodesList.toArray(new NodeGatewayStartedShards[nodesList.size()]),
                failures.toArray(new FailedNodeException[failures.size()]));
    }

    @Override
    protected NodeGatewayStartedShards nodeOperation(NodeRequest request) throws ElasticsearchException {
        try {
            logger.trace("{} loading local shard state info", request.shardId);
            ShardStateInfo shardStateInfo = shardsState.loadShardInfo(request.shardId);
            if (shardStateInfo != null) {
                logger.debug("{} shard state info found: [{}]", request.shardId, shardStateInfo);
                return new NodeGatewayStartedShards(clusterService.localNode(), shardStateInfo.version);
            }
            logger.trace("{} no local shard info found", request.shardId);
            return new NodeGatewayStartedShards(clusterService.localNode(), -1);
        } catch (Exception e) {
            throw new ElasticsearchException("failed to load started shards", e);
        }
    }

    @Override
    protected boolean accumulateExceptions() {
        return true;
    }

    static class Request extends NodesOperationRequest<Request> {

        private ShardId shardId;

        public Request() {
        }

        public Request(ShardId shardId, Set<String> nodesIds) {
            super(nodesIds.toArray(new String[nodesIds.size()]));
            this.shardId = shardId;
        }

        public Request(ShardId shardId, String... nodesIds) {
            super(nodesIds);
            this.shardId = shardId;
        }

        public ShardId shardId() {
            return this.shardId;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            shardId = ShardId.readShardId(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardId.writeTo(out);
        }
    }

    public static class NodesGatewayStartedShards extends NodesOperationResponse<NodeGatewayStartedShards> {

        private FailedNodeException[] failures;

        NodesGatewayStartedShards() {
        }

        public NodesGatewayStartedShards(ClusterName clusterName, NodeGatewayStartedShards[] nodes, FailedNodeException[] failures) {
            super(clusterName, nodes);
            this.failures = failures;
        }

        public FailedNodeException[] failures() {
            return failures;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            nodes = new NodeGatewayStartedShards[in.readVInt()];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = new NodeGatewayStartedShards();
                nodes[i].readFrom(in);
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeVInt(nodes.length);
            for (NodeGatewayStartedShards response : nodes) {
                response.writeTo(out);
            }
        }
    }


    static class NodeRequest extends NodeOperationRequest {

        ShardId shardId;

        NodeRequest() {
        }

        NodeRequest(String nodeId, TransportNodesListGatewayStartedShards.Request request) {
            super(request, nodeId);
            this.shardId = request.shardId();
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            shardId = ShardId.readShardId(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardId.writeTo(out);
        }
    }

    public static class NodeGatewayStartedShards extends NodeOperationResponse {

        private long version = -1;

        NodeGatewayStartedShards() {
        }

        public NodeGatewayStartedShards(DiscoveryNode node, long version) {
            super(node);
            this.version = version;
        }

        public long version() {
            return this.version;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            version = in.readLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeLong(version);
        }
    }
}
