/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transport.actions.stats;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.watcher.WatcherBuild;
import org.elasticsearch.watcher.WatcherState;
import org.elasticsearch.watcher.WatcherVersion;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.watcher.execution.QueuedWatch;
import org.elasticsearch.watcher.execution.WatchExecutionSnapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WatcherStatsResponse extends ActionResponse implements ToXContent {

    private WatcherVersion version;
    private WatcherBuild build;
    private long watchesCount;
    private WatcherState watcherState;
    private long watchExecutionQueueSize;
    private long watchExecutionQueueMaxSize;

    private List<WatchExecutionSnapshot> snapshots;
    private List<QueuedWatch> queuedWatches;

    WatcherStatsResponse() {
    }

    /**
     * @return The current watch execution queue size
     */
    public long getExecutionQueueSize() {
        return watchExecutionQueueSize;
    }

    void setWatchExecutionQueueSize(long watchExecutionQueueSize) {
        this.watchExecutionQueueSize = watchExecutionQueueSize;
    }

    /**
     * @return The max size of the watch execution queue
     */
    public long getWatchExecutionQueueMaxSize() {
        return watchExecutionQueueMaxSize;
    }

    void setWatchExecutionQueueMaxSize(long watchExecutionQueueMaxSize) {
        this.watchExecutionQueueMaxSize = watchExecutionQueueMaxSize;
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

    /**
     * @return The watcher plugin version.
     */
    public WatcherVersion getVersion() {
        return version;
    }

    void setVersion(WatcherVersion version) {
        this.version = version;
    }

    /**
     * @return The watcher plugin build information.
     */
    public WatcherBuild getBuild() {
        return build;
    }

    void setBuild(WatcherBuild build) {
        this.build = build;
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
        watchExecutionQueueSize = in.readLong();
        watchExecutionQueueMaxSize = in.readLong();
        watcherState = WatcherState.fromId(in.readByte());
        version = WatcherVersion.readVersion(in);
        build = WatcherBuild.readBuild(in);

        if (in.readBoolean()) {
            int size = in.readVInt();
            snapshots = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                snapshots.add(new WatchExecutionSnapshot(in));
            }
        }
        if (in.readBoolean()) {
            int size = in.readVInt();
            queuedWatches = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                queuedWatches.add(new QueuedWatch(in));
            }
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeLong(watchesCount);
        out.writeLong(watchExecutionQueueSize);
        out.writeLong(watchExecutionQueueMaxSize);
        out.writeByte(watcherState.getId());
        WatcherVersion.writeVersion(version, out);
        WatcherBuild.writeBuild(build, out);

        if (snapshots != null) {
            out.writeBoolean(true);
            out.writeVInt(snapshots.size());
            for (WatchExecutionSnapshot snapshot : snapshots) {
                snapshot.writeTo(out);
            }
        } else {
            out.writeBoolean(false);
        }
        if (queuedWatches != null) {
            out.writeBoolean(true);
            out.writeVInt(queuedWatches.size());
            for (QueuedWatch pending : this.queuedWatches) {
                pending.writeTo(out);
            }
        } else {
            out.writeBoolean(false);
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field("watcher_state", watcherState.toString().toLowerCase(Locale.ROOT));
        builder.field("watch_count", watchesCount);
        builder.startObject("execution_queue");
        builder.field("size", watchExecutionQueueSize);
        builder.field("max_size", watchExecutionQueueMaxSize);
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
}
