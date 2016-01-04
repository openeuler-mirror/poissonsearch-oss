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

package org.elasticsearch.cluster.action.shard;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.NotMasterException;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.RoutingService;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.cluster.routing.allocation.FailedRerouteAllocation;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.EmptyTransportResponseHandler;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.elasticsearch.cluster.routing.ShardRouting.readShardRoutingEntry;

public class ShardStateAction extends AbstractComponent {
    public static final String SHARD_STARTED_ACTION_NAME = "internal:cluster/shard/started";
    public static final String SHARD_FAILED_ACTION_NAME = "internal:cluster/shard/failure";

    private final TransportService transportService;

    @Inject
    public ShardStateAction(Settings settings, ClusterService clusterService, TransportService transportService,
                            AllocationService allocationService, RoutingService routingService) {
        super(settings);
        this.transportService = transportService;

        transportService.registerRequestHandler(SHARD_STARTED_ACTION_NAME, ShardRoutingEntry::new, ThreadPool.Names.SAME, new ShardStartedTransportHandler(clusterService, new ShardStartedClusterStateTaskExecutor(allocationService, logger), logger));
        transportService.registerRequestHandler(SHARD_FAILED_ACTION_NAME, ShardRoutingEntry::new, ThreadPool.Names.SAME, new ShardFailedTransportHandler(clusterService, new ShardFailedClusterStateTaskExecutor(allocationService, routingService, logger), logger));
    }

    public void shardFailed(final ClusterState clusterState, final ShardRouting shardRouting, final String indexUUID, final String message, @Nullable final Throwable failure, Listener listener) {
        shardFailed(clusterState, shardRouting, indexUUID, message, failure, null, listener);
    }

    public void resendShardFailed(final ClusterState clusterState, final ShardRouting shardRouting, final String indexUUID, final String message, @Nullable final Throwable failure, Listener listener) {
        logger.trace("{} re-sending failed shard [{}], index UUID [{}], reason [{}]", shardRouting.shardId(), failure, shardRouting, indexUUID, message);
        shardFailed(clusterState, shardRouting, indexUUID, message, failure, listener);
    }

    public void shardFailed(final ClusterState clusterState, final ShardRouting shardRouting, final String indexUUID, final String message, @Nullable final Throwable failure, TimeValue timeout, Listener listener) {
        DiscoveryNode masterNode = clusterState.nodes().masterNode();
        if (masterNode == null) {
            logger.warn("{} no master known to fail shard [{}]", shardRouting.shardId(), shardRouting);
            listener.onShardFailedNoMaster();
            return;
        }
        ShardRoutingEntry shardRoutingEntry = new ShardRoutingEntry(shardRouting, indexUUID, message, failure);
        TransportRequestOptions options = TransportRequestOptions.EMPTY;
        if (timeout != null) {
            options = TransportRequestOptions.builder().withTimeout(timeout).build();
        }
        transportService.sendRequest(masterNode,
            SHARD_FAILED_ACTION_NAME, shardRoutingEntry, options, new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {
                @Override
                public void handleResponse(TransportResponse.Empty response) {
                    listener.onSuccess();
                }

                @Override
                public void handleException(TransportException exp) {
                    logger.warn("{} unexpected failure while sending request to [{}] to fail shard [{}]", exp, shardRoutingEntry.shardRouting.shardId(), masterNode, shardRoutingEntry);
                    listener.onShardFailedFailure(masterNode, exp);
                }
            });
    }

    private static class ShardFailedTransportHandler implements TransportRequestHandler<ShardRoutingEntry> {
        private final ClusterService clusterService;
        private final ShardFailedClusterStateTaskExecutor shardFailedClusterStateTaskExecutor;
        private final ESLogger logger;

        public ShardFailedTransportHandler(ClusterService clusterService, ShardFailedClusterStateTaskExecutor shardFailedClusterStateTaskExecutor, ESLogger logger) {
            this.clusterService = clusterService;
            this.shardFailedClusterStateTaskExecutor = shardFailedClusterStateTaskExecutor;
            this.logger = logger;
        }

        @Override
        public void messageReceived(ShardRoutingEntry request, TransportChannel channel) throws Exception {
            logger.warn("{} received shard failed for {}", request.failure, request.shardRouting.shardId(), request);
            clusterService.submitStateUpdateTask(
                "shard-failed (" + request.shardRouting + "), message [" + request.message + "]",
                request,
                ClusterStateTaskConfig.build(Priority.HIGH),
                shardFailedClusterStateTaskExecutor,
                new ClusterStateTaskListener() {
                    @Override
                    public void onFailure(String source, Throwable t) {
                        logger.error("{} unexpected failure while failing shard [{}]", t, request.shardRouting.shardId(), request.shardRouting);
                        try {
                            channel.sendResponse(t);
                        } catch (Throwable channelThrowable) {
                            logger.warn("{} failed to send failure [{}] while failing shard [{}]", channelThrowable, request.shardRouting.shardId(), t, request.shardRouting);
                        }
                    }

                    @Override
                    public void onNoLongerMaster(String source) {
                        logger.error("{} no longer master while failing shard [{}]", request.shardRouting.shardId(), request.shardRouting);
                        try {
                            channel.sendResponse(new NotMasterException(source));
                        } catch (Throwable channelThrowable) {
                            logger.warn("{} failed to send no longer master while failing shard [{}]", channelThrowable, request.shardRouting.shardId(), request.shardRouting);
                        }
                    }

                    @Override
                    public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                        try {
                            channel.sendResponse(TransportResponse.Empty.INSTANCE);
                        } catch (Throwable channelThrowable) {
                            logger.warn("{} failed to send response while failing shard [{}]", channelThrowable, request.shardRouting.shardId(), request.shardRouting);
                        }
                    }
                }
            );
        }
    }

    private static class ShardFailedClusterStateTaskExecutor implements ClusterStateTaskExecutor<ShardRoutingEntry> {
        private final AllocationService allocationService;
        private final RoutingService routingService;
        private final ESLogger logger;

        public ShardFailedClusterStateTaskExecutor(AllocationService allocationService, RoutingService routingService, ESLogger logger) {
            this.allocationService = allocationService;
            this.routingService = routingService;
            this.logger = logger;
        }

        @Override
        public BatchResult<ShardRoutingEntry> execute(ClusterState currentState, List<ShardRoutingEntry> tasks) throws Exception {
            BatchResult.Builder<ShardRoutingEntry> batchResultBuilder = BatchResult.builder();
            List<FailedRerouteAllocation.FailedShard> failedShards = new ArrayList<>(tasks.size());
            for (ShardRoutingEntry task : tasks) {
                failedShards.add(new FailedRerouteAllocation.FailedShard(task.shardRouting, task.message, task.failure));
            }
            ClusterState maybeUpdatedState = currentState;
            try {
                RoutingAllocation.Result result = allocationService.applyFailedShards(currentState, failedShards);
                if (result.changed()) {
                    maybeUpdatedState = ClusterState.builder(currentState).routingResult(result).build();
                }
                batchResultBuilder.successes(tasks);
            } catch (Throwable t) {
                batchResultBuilder.failures(tasks, t);
            }
            return batchResultBuilder.build(maybeUpdatedState);
        }

        @Override
        public void clusterStatePublished(ClusterState newClusterState) {
            int numberOfUnassignedShards = newClusterState.getRoutingNodes().unassigned().size();
            if (numberOfUnassignedShards > 0) {
                String reason = String.format(Locale.ROOT, "[%d] unassigned shards after failing shards", numberOfUnassignedShards);
                if (logger.isTraceEnabled()) {
                    logger.trace(reason + ", scheduling a reroute");
                }
                routingService.reroute(reason);
            }
        }
    }

    public void shardStarted(final ClusterState clusterState, final ShardRouting shardRouting, String indexUUID, final String reason) {
        DiscoveryNode masterNode = clusterState.nodes().masterNode();
        if (masterNode == null) {
            logger.warn("{} no master known to start shard [{}]", shardRouting.shardId(), shardRouting);
            return;
        }
        ShardRoutingEntry shardRoutingEntry = new ShardRoutingEntry(shardRouting, indexUUID, reason, null);
        logger.debug("sending start shard [{}]", shardRoutingEntry);
        transportService.sendRequest(masterNode,
            SHARD_STARTED_ACTION_NAME, new ShardRoutingEntry(shardRouting, indexUUID, reason, null), new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {
                @Override
                public void handleException(TransportException exp) {
                    logger.warn("{} failure sending start shard [{}] to [{}]", exp, shardRouting.shardId(), masterNode, shardRouting);
                }
            });
    }

    private static class ShardStartedTransportHandler implements TransportRequestHandler<ShardRoutingEntry> {
        private final ClusterService clusterService;
        private final ShardStartedClusterStateTaskExecutor shardStartedClusterStateTaskExecutor;
        private final ESLogger logger;

        public ShardStartedTransportHandler(ClusterService clusterService, ShardStartedClusterStateTaskExecutor shardStartedClusterStateTaskExecutor, ESLogger logger) {
            this.clusterService = clusterService;
            this.shardStartedClusterStateTaskExecutor = shardStartedClusterStateTaskExecutor;
            this.logger = logger;
        }

        @Override
        public void messageReceived(ShardRoutingEntry request, TransportChannel channel) throws Exception {
            logger.debug("{} received shard started for [{}]", request.shardRouting.shardId(), request);
            clusterService.submitStateUpdateTask(
                "shard-started (" + request.shardRouting + "), reason [" + request.message + "]",
                request,
                ClusterStateTaskConfig.build(Priority.URGENT),
                shardStartedClusterStateTaskExecutor,
                shardStartedClusterStateTaskExecutor);
            channel.sendResponse(TransportResponse.Empty.INSTANCE);
        }
    }

    private static class ShardStartedClusterStateTaskExecutor implements ClusterStateTaskExecutor<ShardRoutingEntry>, ClusterStateTaskListener {
        private final AllocationService allocationService;
        private final ESLogger logger;

        public ShardStartedClusterStateTaskExecutor(AllocationService allocationService, ESLogger logger) {
            this.allocationService = allocationService;
            this.logger = logger;
        }

        @Override
        public BatchResult<ShardRoutingEntry> execute(ClusterState currentState, List<ShardRoutingEntry> tasks) throws Exception {
            BatchResult.Builder<ShardRoutingEntry> builder = BatchResult.builder();
            List<ShardRouting> shardRoutingsToBeApplied = new ArrayList<>(tasks.size());
            for (ShardRoutingEntry task : tasks) {
                shardRoutingsToBeApplied.add(task.shardRouting);
            }
            ClusterState maybeUpdatedState = currentState;
            try {
                RoutingAllocation.Result result =
                    allocationService.applyStartedShards(currentState, shardRoutingsToBeApplied, true);
                if (result.changed()) {
                    maybeUpdatedState = ClusterState.builder(currentState).routingResult(result).build();
                }
                builder.successes(tasks);
            } catch (Throwable t) {
                builder.failures(tasks, t);
            }

            return builder.build(maybeUpdatedState);
        }

        @Override
        public void onFailure(String source, Throwable t) {
            logger.error("unexpected failure during [{}]", t, source);
        }
    }

    public static class ShardRoutingEntry extends TransportRequest {
        ShardRouting shardRouting;
        String indexUUID = IndexMetaData.INDEX_UUID_NA_VALUE;
        String message;
        Throwable failure;

        public ShardRoutingEntry() {
        }

        ShardRoutingEntry(ShardRouting shardRouting, String indexUUID, String message, @Nullable Throwable failure) {
            this.shardRouting = shardRouting;
            this.indexUUID = indexUUID;
            this.message = message;
            this.failure = failure;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            shardRouting = readShardRoutingEntry(in);
            indexUUID = in.readString();
            message = in.readString();
            failure = in.readThrowable();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            shardRouting.writeTo(out);
            out.writeString(indexUUID);
            out.writeString(message);
            out.writeThrowable(failure);
        }

        @Override
        public String toString() {
            return "" + shardRouting + ", indexUUID [" + indexUUID + "], message [" + message + "], failure [" + ExceptionsHelper.detailedMessage(failure) + "]";
        }
    }

    public interface Listener {
        default void onSuccess() {
        }

        default void onShardFailedNoMaster() {
        }

        default void onShardFailedFailure(final DiscoveryNode master, final TransportException e) {
        }
    }
}
