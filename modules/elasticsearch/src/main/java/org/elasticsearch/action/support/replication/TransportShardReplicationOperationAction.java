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

package org.elasticsearch.action.support.replication;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.PrimaryNotStartedActionException;
import org.elasticsearch.action.support.BaseAction;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.TimeoutClusterStateListener;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardsIterator;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.io.stream.VoidStreamable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexShardMissingException;
import org.elasticsearch.index.shard.IllegalIndexShardStateException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.node.NodeCloseException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.ExceptionsHelper.*;

/**
 * @author kimchy (shay.banon)
 */
public abstract class TransportShardReplicationOperationAction<Request extends ShardReplicationOperationRequest, Response extends ActionResponse> extends BaseAction<Request, Response> {

    protected final TransportService transportService;

    protected final ClusterService clusterService;

    protected final IndicesService indicesService;

    protected final ThreadPool threadPool;

    protected final ShardStateAction shardStateAction;

    protected final ReplicationType defaultReplicationType;

    protected TransportShardReplicationOperationAction(Settings settings, TransportService transportService,
                                                       ClusterService clusterService, IndicesService indicesService,
                                                       ThreadPool threadPool, ShardStateAction shardStateAction) {
        super(settings);
        this.transportService = transportService;
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.threadPool = threadPool;
        this.shardStateAction = shardStateAction;

        transportService.registerHandler(transportAction(), new OperationTransportHandler());
        transportService.registerHandler(transportReplicaAction(), new ReplicaOperationTransportHandler());

        this.defaultReplicationType = ReplicationType.fromString(settings.get("action.replication_type", "sync"));
    }

    @Override protected void doExecute(Request request, ActionListener<Response> listener) {
        new AsyncShardOperationAction(request, listener).start();
    }

    protected abstract Request newRequestInstance();

    protected abstract Response newResponseInstance();

    protected abstract String transportAction();

    protected abstract Response shardOperationOnPrimary(ShardOperationRequest shardRequest);

    protected abstract void shardOperationOnReplica(ShardOperationRequest shardRequest);

    protected abstract ShardsIterator shards(ClusterState clusterState, Request request) throws ElasticSearchException;

    protected void checkBlock(Request request, ClusterState state) {

    }

    /**
     * Should the operations be performed on the replicas as well. Defaults to <tt>false</tt> meaning operations
     * will be executed on the replica.
     */
    protected boolean ignoreReplicas() {
        return false;
    }

    private String transportReplicaAction() {
        return transportAction() + "/replica";
    }

    protected IndexShard indexShard(ShardOperationRequest shardRequest) {
        return indicesService.indexServiceSafe(shardRequest.request.index()).shardSafe(shardRequest.shardId);
    }

    private class OperationTransportHandler extends BaseTransportRequestHandler<Request> {

        @Override public Request newInstance() {
            return newRequestInstance();
        }

        @Override public void messageReceived(final Request request, final TransportChannel channel) throws Exception {
            // no need to have a threaded listener since we just send back a response
            request.listenerThreaded(false);
            // if we have a local operation, execute it on a thread since we don't spawn
            request.operationThreaded(true);
            execute(request, new ActionListener<Response>() {
                @Override public void onResponse(Response result) {
                    try {
                        channel.sendResponse(result);
                    } catch (Exception e) {
                        onFailure(e);
                    }
                }

                @Override public void onFailure(Throwable e) {
                    try {
                        channel.sendResponse(e);
                    } catch (Exception e1) {
                        logger.warn("Failed to send response for " + transportAction(), e1);
                    }
                }
            });
        }

        @Override public boolean spawn() {
            return false;
        }
    }

    private class ReplicaOperationTransportHandler extends BaseTransportRequestHandler<ShardOperationRequest> {

        @Override public ShardOperationRequest newInstance() {
            return new ShardOperationRequest();
        }

        @Override public void messageReceived(ShardOperationRequest request, TransportChannel channel) throws Exception {
            shardOperationOnReplica(request);
            channel.sendResponse(VoidStreamable.INSTANCE);
        }

        /**
         * We spawn, since we want to perform the operation on the replica on a different thread.
         */
        @Override public boolean spawn() {
            return true;
        }
    }

    protected class ShardOperationRequest implements Streamable {

        public int shardId;

        public Request request;

        public ShardOperationRequest() {
        }

        public ShardOperationRequest(int shardId, Request request) {
            this.shardId = shardId;
            this.request = request;
        }

        @Override public void readFrom(StreamInput in) throws IOException {
            shardId = in.readVInt();
            request = newRequestInstance();
            request.readFrom(in);
        }

        @Override public void writeTo(StreamOutput out) throws IOException {
            out.writeVInt(shardId);
            request.writeTo(out);
        }
    }

    private class AsyncShardOperationAction {

        private final ActionListener<Response> listener;

        private final Request request;

        private DiscoveryNodes nodes;

        private ShardsIterator shards;

        private final AtomicBoolean primaryOperationStarted = new AtomicBoolean();

        private final ReplicationType replicationType;

        private AsyncShardOperationAction(Request request, ActionListener<Response> listener) {
            this.request = request;
            this.listener = listener;

            // update to the concrete index
            ClusterState clusterState = clusterService.state();
            request.index(clusterState.metaData().concreteIndex(request.index()));

            checkBlock(request, clusterState);

            if (request.replicationType() != ReplicationType.DEFAULT) {
                replicationType = request.replicationType();
            } else {
                replicationType = defaultReplicationType;
            }
        }

        public void start() {
            start(false);
        }

        /**
         * Returns <tt>true</tt> if the action starting to be performed on the primary (or is done).
         */
        public boolean start(final boolean fromClusterEvent) throws ElasticSearchException {
            ClusterState clusterState = clusterService.state();
            nodes = clusterState.nodes();
            if (!indicesService.hasIndex(request.index()) || !clusterState.routingTable().hasIndex(request.index())) {
                retryPrimary(fromClusterEvent, null);
                return false;
            }
            try {
                shards = shards(clusterState, request);
            } catch (Exception e) {
                listener.onFailure(e);
                return true;
            }

            boolean foundPrimary = false;
            for (final ShardRouting shard : shards) {
                if (shard.primary()) {
                    if (!shard.active() || !nodes.nodeExists(shard.currentNodeId()) || !indicesService.hasIndex(request.index())) {
                        retryPrimary(fromClusterEvent, shard.shardId());
                        return false;
                    }

                    if (!primaryOperationStarted.compareAndSet(false, true)) {
                        return false;
                    }

                    foundPrimary = true;
                    if (shard.currentNodeId().equals(nodes.localNodeId())) {
                        if (request.operationThreaded()) {
                            request.beforeLocalFork();
                            threadPool.execute(new Runnable() {
                                @Override public void run() {
                                    performOnPrimary(shard.id(), fromClusterEvent, true, shard);
                                }
                            });
                        } else {
                            performOnPrimary(shard.id(), fromClusterEvent, false, shard);
                        }
                    } else {
                        DiscoveryNode node = nodes.get(shard.currentNodeId());
                        transportService.sendRequest(node, transportAction(), request, new BaseTransportResponseHandler<Response>() {

                            @Override public Response newInstance() {
                                return newResponseInstance();
                            }

                            @Override public void handleResponse(Response response) {
                                listener.onResponse(response);
                            }

                            @Override public void handleException(RemoteTransportException exp) {
                                // if we got disconnected from the node, or the node / shard is not in the right state (being closed)
                                if (exp.unwrapCause() instanceof ConnectTransportException || exp.unwrapCause() instanceof NodeCloseException ||
                                        exp.unwrapCause() instanceof IllegalIndexShardStateException) {
                                    primaryOperationStarted.set(false);
                                    retryPrimary(fromClusterEvent, shard.shardId());
                                } else {
                                    listener.onFailure(exp);
                                }
                            }

                            @Override public boolean spawn() {
                                return request.listenerThreaded();
                            }
                        });
                    }
                    break;
                }
            }
            // we should never get here, but here we go
            if (!foundPrimary) {
                final PrimaryNotStartedActionException failure = new PrimaryNotStartedActionException(shards.shardId(), "Primary not found");
                if (request.listenerThreaded()) {
                    threadPool.execute(new Runnable() {
                        @Override public void run() {
                            listener.onFailure(failure);
                        }
                    });
                } else {
                    listener.onFailure(failure);
                }
            }
            return true;
        }

        private void retryPrimary(boolean fromClusterEvent, final ShardId shardId) {
            if (!fromClusterEvent) {
                // make it threaded operation so we fork on the discovery listener thread
                request.operationThreaded(true);
                clusterService.add(request.timeout(), new TimeoutClusterStateListener() {
                    @Override public void postAdded() {
                        if (start(true)) {
                            // if we managed to start and perform the operation on the primary, we can remove this listener
                            clusterService.remove(this);
                        }
                    }

                    @Override public void onClose() {
                        clusterService.remove(this);
                        listener.onFailure(new NodeCloseException(nodes.localNode()));
                    }

                    @Override public void clusterChanged(ClusterChangedEvent event) {
                        if (start(true)) {
                            // if we managed to start and perform the operation on the primary, we can remove this listener
                            clusterService.remove(this);
                        }
                    }

                    @Override public void onTimeout(TimeValue timeValue) {
                        // just to be on the safe side, see if we can start it now?
                        if (start(true)) {
                            clusterService.remove(this);
                            return;
                        }
                        clusterService.remove(this);
                        final PrimaryNotStartedActionException failure = new PrimaryNotStartedActionException(shardId, "Timeout waiting for [" + timeValue + "]");
                        if (request.listenerThreaded()) {
                            threadPool.execute(new Runnable() {
                                @Override public void run() {
                                    listener.onFailure(failure);
                                }
                            });
                        } else {
                            listener.onFailure(failure);
                        }
                    }
                });
            }
        }

        private void performOnPrimary(int primaryShardId, boolean fromDiscoveryListener, boolean alreadyThreaded, final ShardRouting shard) {
            try {
                Response response = shardOperationOnPrimary(new ShardOperationRequest(primaryShardId, request));
                performReplicas(response, alreadyThreaded);
            } catch (Exception e) {
                // shard has not been allocated yet, retry it here
                if (e instanceof IndexShardMissingException || e instanceof IllegalIndexShardStateException || e instanceof IndexMissingException) {
                    retryPrimary(fromDiscoveryListener, shard.shardId());
                    return;
                }
                if (logger.isDebugEnabled()) {
                    logger.debug(shard.shortSummary() + ": Failed to execute [" + request + "]", e);
                }
                listener.onFailure(new ReplicationShardOperationFailedException(shards.shardId(), e));
            }
        }

        private void performReplicas(final Response response, boolean alreadyThreaded) {
            if (ignoreReplicas() || shards.size() == 1 /* no replicas */) {
                if (alreadyThreaded || !request.listenerThreaded()) {
                    listener.onResponse(response);
                } else {
                    threadPool.execute(new Runnable() {
                        @Override public void run() {
                            listener.onResponse(response);
                        }
                    });
                }
                return;
            }

            // initialize the counter
            int replicaCounter = 0;

            if (replicationType == ReplicationType.ASYNC) {
                // async replication, notify the listener
                if (alreadyThreaded || !request.listenerThreaded()) {
                    listener.onResponse(response);
                } else {
                    threadPool.execute(new Runnable() {
                        @Override public void run() {
                            listener.onResponse(response);
                        }
                    });
                }
                // now, trick the counter so it won't decrease to 0
                replicaCounter = -100;
            }

            for (final ShardRouting shard : shards.reset()) {
                // if the shard is primary and relocating, add one to the counter since we perform it on the replica as well
                if (shard.primary()) {
                    if (shard.relocating()) {
                        replicaCounter++;
                    }
                } else {
                    replicaCounter++;
                    // if we are relocating the replica, we want to perform the index operation on both the relocating
                    // shard and the target shard. This means that we won't loose index operations between end of recovery
                    // and reassignment of the shard by the master node
                    if (shard.relocating()) {
                        replicaCounter++;
                    }
                }
            }

            AtomicInteger counter = new AtomicInteger(replicaCounter);
            for (final ShardRouting shard : shards.reset()) {
                boolean doOnlyOnRelocating = false;
                if (shard.primary()) {
                    if (shard.relocating()) {
                        doOnlyOnRelocating = true;
                    } else {
                        continue;
                    }
                }
                // we index on a replica that is initializing as well since we might not have got the event
                // yet that it was started. We will get an exception IllegalShardState exception if its not started
                // and that's fine, we will ignore it

                // if we don't have that node, it means that it might have failed and will be created again, in
                // this case, we don't have to do the operation, and just let it failover
                if (shard.unassigned() || !nodes.nodeExists(shard.currentNodeId())) {
                    if (counter.decrementAndGet() == 0) {
                        if (alreadyThreaded || !request.listenerThreaded()) {
                            listener.onResponse(response);
                        } else {
                            threadPool.execute(new Runnable() {
                                @Override public void run() {
                                    listener.onResponse(response);
                                }
                            });
                        }
                        break;
                    }
                    continue;
                }
                if (!doOnlyOnRelocating) {
                    performOnReplica(response, counter, shard, shard.currentNodeId());
                }
                if (shard.relocating()) {
                    performOnReplica(response, counter, shard, shard.relocatingNodeId());
                }
            }
        }

        private void performOnReplica(final Response response, final AtomicInteger counter, final ShardRouting shard, String nodeId) {
            final ShardOperationRequest shardRequest = new ShardOperationRequest(shards.shardId().id(), request);
            if (!nodeId.equals(nodes.localNodeId())) {
                DiscoveryNode node = nodes.get(nodeId);
                transportService.sendRequest(node, transportReplicaAction(), shardRequest, new VoidTransportResponseHandler() {
                    @Override public void handleResponse(VoidStreamable vResponse) {
                        finishIfPossible();
                    }

                    @Override public void handleException(RemoteTransportException exp) {
                        if (!ignoreReplicaException(exp.unwrapCause())) {
                            logger.warn("Failed to perform " + transportAction() + " on replica " + shards.shardId(), exp);
                            shardStateAction.shardFailed(shard, "Failed to perform [" + transportAction() + "] on replica, message [" + detailedMessage(exp) + "]");
                        }
                        finishIfPossible();
                    }

                    private void finishIfPossible() {
                        if (counter.decrementAndGet() == 0) {
                            if (request.listenerThreaded()) {
                                threadPool.execute(new Runnable() {
                                    @Override public void run() {
                                        listener.onResponse(response);
                                    }
                                });
                            } else {
                                listener.onResponse(response);
                            }
                        }
                    }

                    @Override public boolean spawn() {
                        // don't spawn, we will call the listener on a thread pool if needed
                        return false;
                    }
                });
            } else {
                if (request.operationThreaded()) {
                    request.beforeLocalFork();
                    threadPool.execute(new Runnable() {
                        @Override public void run() {
                            try {
                                shardOperationOnReplica(shardRequest);
                            } catch (Exception e) {
                                if (!ignoreReplicaException(e)) {
                                    logger.warn("Failed to perform " + transportAction() + " on replica " + shards.shardId(), e);
                                    shardStateAction.shardFailed(shard, "Failed to perform [" + transportAction() + "] on replica, message [" + detailedMessage(e) + "]");
                                }
                            }
                            if (counter.decrementAndGet() == 0) {
                                listener.onResponse(response);
                            }
                        }
                    });
                } else {
                    try {
                        shardOperationOnReplica(shardRequest);
                    } catch (Exception e) {
                        if (!ignoreReplicaException(e)) {
                            logger.warn("Failed to perform " + transportAction() + " on replica" + shards.shardId(), e);
                            shardStateAction.shardFailed(shard, "Failed to perform [" + transportAction() + "] on replica, message [" + detailedMessage(e) + "]");
                        }
                    }
                    if (counter.decrementAndGet() == 0) {
                        if (request.listenerThreaded()) {
                            threadPool.execute(new Runnable() {
                                @Override public void run() {
                                    listener.onResponse(response);
                                }
                            });
                        } else {
                            listener.onResponse(response);
                        }
                    }
                }
            }
        }

        /**
         * Should an exception be ignored when the operation is performed on the replica.
         */
        private boolean ignoreReplicaException(Throwable e) {
            Throwable cause = ExceptionsHelper.unwrapCause(e);
            if (cause instanceof IllegalIndexShardStateException) {
                return true;
            }
            if (cause instanceof IndexMissingException) {
                return true;
            }
            if (cause instanceof IndexShardMissingException) {
                return true;
            }
            if (cause instanceof ConnectTransportException) {
                return true;
            }
            return false;
        }
    }
}
