/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.transport.actions.stats;

import org.elasticsearch.Version;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.nodes.BaseNodeResponse;
import org.elasticsearch.action.support.nodes.BaseNodesResponse;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.xpack.watcher.WatcherMetaData;
import org.elasticsearch.xpack.watcher.WatcherState;
import org.elasticsearch.xpack.watcher.execution.QueuedWatch;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public class WatcherStatsResponse extends BaseNodesResponse<WatcherStatsResponse.Node>
        implements ToXContentObject {

    private WatcherMetaData watcherMetaData;

    public WatcherStatsResponse() {
    }

    public WatcherStatsResponse(ClusterName clusterName, WatcherMetaData watcherMetaData,
                                List<Node> nodes, List<FailedNodeException> failures) {
        super(clusterName, nodes, failures);
        this.watcherMetaData = watcherMetaData;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
//        if (out.getVersion().after(Version.V_6_0_0_alpha1_UNRELEASED)) {
            super.writeTo(out);
            out.writeBoolean(watcherMetaData.manuallyStopped());
            /*
        } else {
            // BWC layer for older versions, this is not considered exact
            // this mimics the behaviour of 5.x
            out.writeLong(getNodes().stream().mapToLong(Node::getWatchesCount).sum());
            out.writeLong(getNodes().stream().mapToLong(Node::getThreadPoolQueueSize).sum());
            out.writeLong(getNodes().stream().mapToLong(Node::getThreadPoolMaxSize).sum());
            // byte, watcher state, cannot be exact, just pick the first
            out.writeByte(getNodes().get(0).getWatcherState().getId());

            out.writeString(Version.CURRENT.toString()); // version
            out.writeString(XPackBuild.CURRENT.shortHash()); // hash
            out.writeString(XPackBuild.CURRENT.shortHash()); // short hash
            out.writeString(XPackBuild.CURRENT.date()); // date

            List<WatchExecutionSnapshot> snapshots = getNodes().stream().map(Node::getSnapshots)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
            if (snapshots != null) {
                out.writeBoolean(true);
                out.writeVInt(snapshots.size());
                for (WatchExecutionSnapshot snapshot : snapshots) {
                    snapshot.writeTo(out);
                }
            } else {
                out.writeBoolean(false);
            }

            List<QueuedWatch> queuedWatches = getNodes().stream().map(Node::getQueuedWatches)
                    .flatMap(List::stream)
                    .collect(Collectors.toList());
            if (queuedWatches != null) {
                out.writeBoolean(true);
                out.writeVInt(queuedWatches.size());
                for (QueuedWatch pending : queuedWatches) {
                    pending.writeTo(out);
                }
            } else {
                out.writeBoolean(false);
            }

            watcherMetaData.writeTo(out);
        }
        */
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        if (in.getVersion().onOrAfter(Version.V_6_0_0_alpha1)) {
            super.readFrom(in);
            watcherMetaData = new WatcherMetaData(in.readBoolean());
        } else {
            // TODO what to do here? create another BWC helping stuff here...
        }
    }

    @Override
    protected List<Node> readNodesFrom(StreamInput in) throws IOException {
        return in.readList(Node::readNodeResponse);
    }

    @Override
    protected void writeNodesTo(StreamOutput out, List<Node> nodes) throws IOException {
        out.writeStreamableList(nodes);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        watcherMetaData.toXContent(builder, params);
        builder.startArray("stats");
        for (Node node : getNodes()) {
            node.toXContent(builder, params);
        }
        builder.endArray();

        return builder;
    }

    /**
     * Sum all watches across all nodes to get a total count of watches in the cluster
     *
     * @return The sum of all watches being executed
     */
    public long getWatchesCount() {
        return getNodes().stream().mapToLong(WatcherStatsResponse.Node::getWatchesCount).sum();
    }

    public WatcherMetaData watcherMetaData() {
        return watcherMetaData;
    }

    public static class Node extends BaseNodeResponse implements ToXContentObject {

        private long watchesCount;
        private WatcherState watcherState;
        private long threadPoolQueueSize;
        private long threadPoolMaxSize;
        private List<WatchExecutionSnapshot> snapshots;
        private List<QueuedWatch> queuedWatches;

        Node() {
        }

        Node(DiscoveryNode node) {
            super(node);
        }

        /**
         * @return The current execution thread pool queue size
         */
        public long getThreadPoolQueueSize() {
            return threadPoolQueueSize;
        }

        void setThreadPoolQueueSize(long threadPoolQueueSize) {
            this.threadPoolQueueSize = threadPoolQueueSize;
        }

        /**
         * @return The max number of threads in the execution thread pool
         */
        public long getThreadPoolMaxSize() {
            return threadPoolMaxSize;
        }

        void setThreadPoolMaxSize(long threadPoolMaxSize) {
            this.threadPoolMaxSize = threadPoolMaxSize;
        }

        /**
         * @return The number of watches currently registered in the system
         */
        public long getWatchesCount() {
            return watchesCount;
        }

        void setWatchesCount(long watchesCount) {
            this.watchesCount = watchesCount;
        }

        /**
         * @return The state of the watch service.
         */
        public WatcherState getWatcherState() {
            return watcherState;
        }

        void setWatcherState(WatcherState watcherServiceState) {
            this.watcherState = watcherServiceState;
        }

        @Nullable
        public List<WatchExecutionSnapshot> getSnapshots() {
            return snapshots;
        }

        void setSnapshots(List<WatchExecutionSnapshot> snapshots) {
            this.snapshots = snapshots;
        }

        @Nullable
        public List<QueuedWatch> getQueuedWatches() {
            return queuedWatches;
        }

        public void setQueuedWatches(List<QueuedWatch> queuedWatches) {
            this.queuedWatches = queuedWatches;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            watchesCount = in.readLong();
            threadPoolQueueSize = in.readLong();
            threadPoolMaxSize = in.readLong();
            watcherState = WatcherState.fromId(in.readByte());

            if (in.readBoolean()) {
                snapshots = in.readStreamableList(WatchExecutionSnapshot::new);
            }
            if (in.readBoolean()) {
                queuedWatches = in.readStreamableList(QueuedWatch::new);
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeLong(watchesCount);
            out.writeLong(threadPoolQueueSize);
            out.writeLong(threadPoolMaxSize);
            out.writeByte(watcherState.getId());

            if (snapshots != null) {
                out.writeBoolean(true);
                out.writeStreamableList(snapshots);
            } else {
                out.writeBoolean(false);
            }
            if (queuedWatches != null) {
                out.writeBoolean(true);
                out.writeStreamableList(queuedWatches);
            } else {
                out.writeBoolean(false);
            }
        }


        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params)
                throws IOException {
            builder.startObject();
            builder.field("node_id", getNode().getId());
            builder.field("watcher_state", watcherState.toString().toLowerCase(Locale.ROOT));
            builder.field("watch_count", watchesCount);
            builder.startObject("execution_thread_pool");
            builder.field("queue_size", threadPoolQueueSize);
            builder.field("max_size", threadPoolMaxSize);
            builder.endObject();

            if (snapshots != null) {
                builder.startArray("current_watches");
                for (WatchExecutionSnapshot snapshot : snapshots) {
                    snapshot.toXContent(builder, params);
                }
                builder.endArray();
            }
            if (queuedWatches != null) {
                builder.startArray("queued_watches");
                for (QueuedWatch queuedWatch : queuedWatches) {
                    queuedWatch.toXContent(builder, params);
                }
                builder.endArray();
            }
            builder.endObject();
            return builder;
        }

        public static WatcherStatsResponse.Node readNodeResponse(StreamInput in)
                throws IOException {
            WatcherStatsResponse.Node node = new WatcherStatsResponse.Node();
            node.readFrom(in);
            return node;
        }
    }
}