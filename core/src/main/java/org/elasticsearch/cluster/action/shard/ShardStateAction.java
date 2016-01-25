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
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.ClusterStateTaskConfig;
import org.elasticsearch.cluster.ClusterStateTaskExecutor;
import org.elasticsearch.cluster.ClusterStateTaskListener;
import org.elasticsearch.cluster.MasterNodeChangePredicate;
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
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.node.NodeClosedException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.EmptyTransportResponseHandler;
import org.elasticsearch.transport.NodeDisconnectedException;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestHandler;
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
    private final ClusterService clusterService;

    @Inject
    public ShardStateAction(Settings settings, ClusterService clusterService, TransportService transportService,
                            AllocationService allocationService, RoutingService routingService) {
        super(settings);
        this.transportService = transportService;
        this.clusterService = clusterService;

        transportService.registerRequestHandler(SHARD_STARTED_ACTION_NAME, ShardRoutingEntry::new, ThreadPool.Names.SAME, new ShardStartedTransportHandler(clusterService, new ShardStartedClusterStateTaskExecutor(allocationService, logger), logger));
        transportService.registerRequestHandler(SHARD_FAILED_ACTION_NAME, ShardRoutingEntry::new, ThreadPool.Names.SAME, new ShardFailedTransportHandler(clusterService, new ShardFailedClusterStateTaskExecutor(allocationService, routingService, logger), logger));
    }

    private void sendShardAction(final String actionName, final ClusterStateObserver observer, final ShardRoutingEntry shardRoutingEntry, final Listener listener) {
        DiscoveryNode masterNode = observer.observedState().nodes().masterNode();
        if (masterNode == null) {
            logger.warn("{} no master known for action [{}] for shard [{}]", shardRoutingEntry.getShardRouting().shardId(), actionName, shardRoutingEntry.getShardRouting());
            waitForNewMasterAndRetry(actionName, observer, shardRoutingEntry, listener);
        } else {
            logger.debug("{} sending [{}] to [{}] for shard [{}]", shardRoutingEntry.getShardRouting().getId(), actionName, masterNode.getId(), shardRoutingEntry);
            transportService.sendRequest(masterNode,
                actionName, shardRoutingEntry, new EmptyTransportResponseHandler(ThreadPool.Names.SAME) {
                    @Override
                    public void handleResponse(TransportResponse.Empty response) {
                        listener.onSuccess();
                    }

                    @Override
                    public void handleException(TransportException exp) {
                        if (isMasterChannelException(exp)) {
                            waitForNewMasterAndRetry(actionName, observer, shardRoutingEntry, listener);
                        } else {
                            logger.warn("{} unexpected failure while sending request [{}] to [{}] for shard [{}]", exp, shardRoutingEntry.getShardRouting().shardId(), actionName, masterNode, shardRoutingEntry);
                            listener.onFailure(exp.getCause());
                        }
                    }
                });
        }
    }

    private static Class[] MASTER_CHANNEL_EXCEPTIONS = new Class[]{
        NotMasterException.class,
        ConnectTransportException.class,
        Discovery.FailedToCommitClusterStateException.class
    };

    private static boolean isMasterChannelException(TransportException exp) {
        return ExceptionsHelper.unwrap(exp, MASTER_CHANNEL_EXCEPTIONS) != null;
    }

    public void shardFailed(final ShardRouting shardRouting, final String indexUUID, final String message, @Nullable final Throwable failure, Listener listener) {
        ClusterStateObserver observer = new ClusterStateObserver(clusterService, null, logger);
        ShardRoutingEntry shardRoutingEntry = new ShardRoutingEntry(shardRouting, indexUUID, message, failure);
        sendShardAction(SHARD_FAILED_ACTION_NAME, observer, shardRoutingEntry, listener);
    }

    public void resendShardFailed(final ShardRouting shardRouting, final String indexUUID, final String message, @Nullable final Throwable failure, Listener listener) {
        logger.trace("{} re-sending failed shard [{}], index UUID [{}], reason [{}]", shardRouting.shardId(), failure, shardRouting, indexUUID, message);
        shardFailed(shardRouting, indexUUID, message, failure, listener);
    }

    // visible for testing
    protected void waitForNewMasterAndRetry(String actionName, ClusterStateObserver observer, ShardRoutingEntry shardRoutingEntry, Listener listener) {
        observer.waitForNextChange(new ClusterStateObserver.Listener() {
            @Override
            public void onNewClusterState(ClusterState state) {
                if (logger.isTraceEnabled()) {
                    logger.trace("new cluster state [{}] after waiting for master election to fail shard [{}]", shardRoutingEntry.getShardRouting().shardId(), state.prettyPrint(), shardRoutingEntry);
                }
                sendShardAction(actionName, observer, shardRoutingEntry, listener);
            }

            @Override
            public void onClusterServiceClose() {
                logger.warn("{} node closed while execution action [{}] for shard [{}]", shardRoutingEntry.failure, shardRoutingEntry.getShardRouting().getId(), actionName, shardRoutingEntry.getShardRouting());
                listener.onFailure(new NodeClosedException(clusterService.localNode()));
            }

            @Override
            public void onTimeout(TimeValue timeout) {
                // we wait indefinitely for a new master
                assert false;
            }
        }, MasterNodeChangePredicate.INSTANCE);
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

    public void shardStarted(final ShardRouting shardRouting, String indexUUID, final String message, Listener listener) {
        ClusterStateObserver observer = new ClusterStateObserver(clusterService, null, logger);
        ShardRoutingEntry shardRoutingEntry = new ShardRoutingEntry(shardRouting, indexUUID, message, null);
        sendShardAction(SHARD_STARTED_ACTION_NAME, observer, shardRoutingEntry, listener);
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

        public ShardRouting getShardRouting() {
            return shardRouting;
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

        /**
         * Notification for non-channel exceptions that are not handled
         * by {@link ShardStateAction}.
         *
         * The exceptions that are handled by {@link ShardStateAction}
         * are:
         *  - {@link NotMasterException}
         *  - {@link NodeDisconnectedException}
         *  - {@link Discovery.FailedToCommitClusterStateException}
         *
         * Any other exception is communicated to the requester via
         * this notification.
         *
         * @param t the unexpected cause of the failure on the master
         */
        default void onFailure(final Throwable t) {
        }
    }

}
