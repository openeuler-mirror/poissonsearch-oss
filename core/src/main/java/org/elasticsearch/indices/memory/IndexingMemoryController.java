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

package org.elasticsearch.indices.memory;

import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.engine.EngineClosedException;
import org.elasticsearch.index.engine.EngineConfig;
import org.elasticsearch.index.engine.FlushNotAllowedEngineException;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/**
 *
 */
public class IndexingMemoryController extends AbstractLifecycleComponent<IndexingMemoryController> {

    public static final String INDEX_BUFFER_SIZE_SETTING = "indices.memory.index_buffer_size";

    private final ThreadPool threadPool;
    private final IndicesService indicesService;

    private final ByteSizeValue indexingBuffer;
    private final ByteSizeValue minShardIndexBufferSize;
    private final ByteSizeValue maxShardIndexBufferSize;

    private final ByteSizeValue translogBuffer;
    private final ByteSizeValue minShardTranslogBufferSize;
    private final ByteSizeValue maxShardTranslogBufferSize;

    private final TimeValue inactiveTime;
    private final TimeValue interval;

    private volatile ScheduledFuture scheduler;

    private static final EnumSet<IndexShardState> CAN_UPDATE_INDEX_BUFFER_STATES = EnumSet.of(
            IndexShardState.RECOVERING, IndexShardState.POST_RECOVERY, IndexShardState.STARTED, IndexShardState.RELOCATED);

    @Inject
    public IndexingMemoryController(Settings settings, ThreadPool threadPool, IndicesService indicesService) {
        super(settings);
        this.threadPool = threadPool;
        this.indicesService = indicesService;

        ByteSizeValue indexingBuffer;
        String indexingBufferSetting = this.settings.get(INDEX_BUFFER_SIZE_SETTING, "10%");
        if (indexingBufferSetting.endsWith("%")) {
            double percent = Double.parseDouble(indexingBufferSetting.substring(0, indexingBufferSetting.length() - 1));
            indexingBuffer = new ByteSizeValue((long) (((double) JvmInfo.jvmInfo().getMem().getHeapMax().bytes()) * (percent / 100)));
            ByteSizeValue minIndexingBuffer = this.settings.getAsBytesSize("indices.memory.min_index_buffer_size", new ByteSizeValue(48, ByteSizeUnit.MB));
            ByteSizeValue maxIndexingBuffer = this.settings.getAsBytesSize("indices.memory.max_index_buffer_size", null);

            if (indexingBuffer.bytes() < minIndexingBuffer.bytes()) {
                indexingBuffer = minIndexingBuffer;
            }
            if (maxIndexingBuffer != null && indexingBuffer.bytes() > maxIndexingBuffer.bytes()) {
                indexingBuffer = maxIndexingBuffer;
            }
        } else {
            indexingBuffer = ByteSizeValue.parseBytesSizeValue(indexingBufferSetting, INDEX_BUFFER_SIZE_SETTING);
        }
        this.indexingBuffer = indexingBuffer;
        this.minShardIndexBufferSize = this.settings.getAsBytesSize("indices.memory.min_shard_index_buffer_size", new ByteSizeValue(4, ByteSizeUnit.MB));
        // LUCENE MONITOR: Based on this thread, currently (based on Mike), having a large buffer does not make a lot of sense: https://issues.apache.org/jira/browse/LUCENE-2324?focusedCommentId=13005155&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-13005155
        this.maxShardIndexBufferSize = this.settings.getAsBytesSize("indices.memory.max_shard_index_buffer_size", new ByteSizeValue(512, ByteSizeUnit.MB));

        ByteSizeValue translogBuffer;
        String translogBufferSetting = this.settings.get("indices.memory.translog_buffer_size", "1%");
        if (translogBufferSetting.endsWith("%")) {
            double percent = Double.parseDouble(translogBufferSetting.substring(0, translogBufferSetting.length() - 1));
            translogBuffer = new ByteSizeValue((long) (((double) JvmInfo.jvmInfo().getMem().getHeapMax().bytes()) * (percent / 100)));
            ByteSizeValue minTranslogBuffer = this.settings.getAsBytesSize("indices.memory.min_translog_buffer_size", new ByteSizeValue(256, ByteSizeUnit.KB));
            ByteSizeValue maxTranslogBuffer = this.settings.getAsBytesSize("indices.memory.max_translog_buffer_size", null);

            if (translogBuffer.bytes() < minTranslogBuffer.bytes()) {
                translogBuffer = minTranslogBuffer;
            }
            if (maxTranslogBuffer != null && translogBuffer.bytes() > maxTranslogBuffer.bytes()) {
                translogBuffer = maxTranslogBuffer;
            }
        } else {
            translogBuffer = ByteSizeValue.parseBytesSizeValue(translogBufferSetting, null);
        }
        this.translogBuffer = translogBuffer;
        this.minShardTranslogBufferSize = this.settings.getAsBytesSize("indices.memory.min_shard_translog_buffer_size", new ByteSizeValue(2, ByteSizeUnit.KB));
        this.maxShardTranslogBufferSize = this.settings.getAsBytesSize("indices.memory.max_shard_translog_buffer_size", new ByteSizeValue(64, ByteSizeUnit.KB));

        this.inactiveTime = this.settings.getAsTime("indices.memory.shard_inactive_time", TimeValue.timeValueMinutes(5));
        // we need to have this relatively small to move a shard from inactive to active fast (enough)
        this.interval = this.settings.getAsTime("indices.memory.interval", TimeValue.timeValueSeconds(30));

        logger.debug("using index_buffer_size [{}], with min_shard_index_buffer_size [{}], max_shard_index_buffer_size [{}], shard_inactive_time [{}], indices.memory.interval [{}]",
                     this.indexingBuffer, this.minShardIndexBufferSize, this.maxShardIndexBufferSize, this.inactiveTime, this.interval);

    }

    @Override
    protected void doStart() {
        // its fine to run it on the scheduler thread, no busy work
        this.scheduler = threadPool.scheduleWithFixedDelay(new ShardsIndicesStatusChecker(), interval);
    }

    @Override
    protected void doStop() {
        FutureUtils.cancel(scheduler);
        scheduler = null;
    }

    @Override
    protected void doClose() {
    }

    /**
     * returns the current budget for the total amount of indexing buffers of
     * active shards on this node
     */
    public ByteSizeValue indexingBufferSize() {
        return indexingBuffer;
    }

    class ShardsIndicesStatusChecker implements Runnable {

        private final Map<ShardId, ShardIndexingStatus> shardsIndicesStatus = new HashMap<>();

        @Override
        public void run() {
            EnumSet<ShardStatusChangeType> changes = purgeDeletedAndClosedShards();

            final List<IndexShard> activeToInactiveIndexingShards = new ArrayList<>();
            final int activeShards = updateShardStatuses(changes, activeToInactiveIndexingShards);
            for (IndexShard indexShard : activeToInactiveIndexingShards) {
                // update inactive indexing buffer size
                try {
                    indexShard.markAsInactive();
                } catch (EngineClosedException e) {
                    // ignore
                    logger.debug("ignore EngineClosedException while marking shard [{}][{}] as inactive", indexShard.shardId().index().name(), indexShard.shardId().id());
                } catch (FlushNotAllowedEngineException e) {
                    // ignore
                    logger.debug("ignore FlushNotAllowedException while marking shard [{}][{}] as inactive", indexShard.shardId().index().name(), indexShard.shardId().id());
                }
            }

            if (changes.isEmpty() == false) {
                // Something changed: recompute indexing buffers:
                calcAndSetShardBuffers(activeShards, "[" + changes + "]");
            }
        }

        /**
         * goes through all existing shards and check whether the changes their active status
         *
         * @return the current count of active shards
         */
        private int updateShardStatuses(EnumSet<ShardStatusChangeType> changes, List<IndexShard> activeToInactiveIndexingShards) {
            int activeShards = 0;
            for (IndexService indexService : indicesService) {
                for (IndexShard indexShard : indexService) {

                    if (!CAN_UPDATE_INDEX_BUFFER_STATES.contains(indexShard.state())) {
                        // not ready to be updated yet
                        continue;
                    }

                    if (indexShard.canIndex() == false) {
                        // shadow replica doesn't have an indexing buffer
                        continue;
                    }

                    final Translog translog;
                    try {
                        translog = indexShard.engine().getTranslog();
                    } catch (EngineClosedException e) {
                        // not ready yet to be checked for activity
                        continue;
                    }

                    final long timeMS = threadPool.estimatedTimeInMillis();

                    ShardIndexingStatus status = shardsIndicesStatus.get(indexShard.shardId());
                    if (status == null) {
                        status = new ShardIndexingStatus();
                        shardsIndicesStatus.put(indexShard.shardId(), status);
                        changes.add(ShardStatusChangeType.ADDED);
                    }

                    // consider shard inactive if it has same translogFileGeneration and no operations for a long time
                    if (status.translogId == translog.currentFileGeneration() && translog.totalOperations() == 0) {
                        if (status.timeMS == -1) {
                            // first time we noticed the shard become idle
                            status.timeMS = timeMS;
                        }
                        // mark it as inactive only if enough time has passed
                        if (status.activeIndexing && (timeMS - status.timeMS) > inactiveTime.millis()) {
                            // inactive for this amount of time, mark it
                            activeToInactiveIndexingShards.add(indexShard);
                            status.activeIndexing = false;
                            changes.add(ShardStatusChangeType.BECAME_INACTIVE);
                            logger.debug("marking shard [{}][{}] as inactive (inactive_time[{}]) indexing wise, setting size to [{}]",
                                         indexShard.shardId().index().name(), indexShard.shardId().id(),
                                         inactiveTime, EngineConfig.INACTIVE_SHARD_INDEXING_BUFFER);
                        }
                    } else {
                        if (!status.activeIndexing) {
                            status.activeIndexing = true;
                            changes.add(ShardStatusChangeType.BECAME_ACTIVE);
                            logger.debug("marking shard [{}][{}] as active indexing wise", indexShard.shardId().index().name(), indexShard.shardId().id());
                        }
                        status.timeMS = -1;
                    }
                    status.translogId = translog.currentFileGeneration();

                    if (status.activeIndexing) {
                        activeShards++;
                    }
                }
            }
            return activeShards;
        }

        /**
         * purge any existing statuses that are no longer updated
         *
         * @return true if any change
         */
        private EnumSet<ShardStatusChangeType> purgeDeletedAndClosedShards() {
            EnumSet<ShardStatusChangeType> changes = EnumSet.noneOf(ShardStatusChangeType.class);

            Iterator<ShardId> statusShardIdIterator = shardsIndicesStatus.keySet().iterator();
            while (statusShardIdIterator.hasNext()) {
                ShardId statusShardId = statusShardIdIterator.next();
                IndexService indexService = indicesService.indexService(statusShardId.getIndex());
                boolean remove;
                if (indexService == null) {
                    remove = true;
                } else {
                    IndexShard indexShard = indexService.shard(statusShardId.id());
                    if (indexShard == null) {
                        remove = true;
                    } else {
                        remove = !CAN_UPDATE_INDEX_BUFFER_STATES.contains(indexShard.state());
                    }
                }
                if (remove) {
                    changes.add(ShardStatusChangeType.DELETED);
                    statusShardIdIterator.remove();
                }
            }
            return changes;
        }

        private void calcAndSetShardBuffers(int activeShards, String reason) {
            if (activeShards == 0) {
                logger.debug("no active shards (reason={})", reason);
                return;
            }
            ByteSizeValue shardIndexingBufferSize = new ByteSizeValue(indexingBuffer.bytes() / activeShards);
            if (shardIndexingBufferSize.bytes() < minShardIndexBufferSize.bytes()) {
                shardIndexingBufferSize = minShardIndexBufferSize;
            }
            if (shardIndexingBufferSize.bytes() > maxShardIndexBufferSize.bytes()) {
                shardIndexingBufferSize = maxShardIndexBufferSize;
            }

            ByteSizeValue shardTranslogBufferSize = new ByteSizeValue(translogBuffer.bytes() / activeShards);
            if (shardTranslogBufferSize.bytes() < minShardTranslogBufferSize.bytes()) {
                shardTranslogBufferSize = minShardTranslogBufferSize;
            }
            if (shardTranslogBufferSize.bytes() > maxShardTranslogBufferSize.bytes()) {
                shardTranslogBufferSize = maxShardTranslogBufferSize;
            }

            logger.debug("recalculating shard indexing buffer (reason={}), total is [{}] with [{}] active shards, each shard set to indexing=[{}], translog=[{}]", reason, indexingBuffer, activeShards, shardIndexingBufferSize, shardTranslogBufferSize);
            for (IndexService indexService : indicesService) {
                for (IndexShard indexShard : indexService) {
                    IndexShardState state = indexShard.state();
                    if (!CAN_UPDATE_INDEX_BUFFER_STATES.contains(state)) {
                        logger.trace("shard [{}] is not yet ready for index buffer update. index shard state: [{}]", indexShard.shardId(), state);
                        continue;
                    }
                    ShardIndexingStatus status = shardsIndicesStatus.get(indexShard.shardId());
                    if (status == null || status.activeIndexing) {
                        try {
                            indexShard.updateBufferSize(shardIndexingBufferSize, shardTranslogBufferSize);
                        } catch (EngineClosedException e) {
                            // ignore
                            continue;
                        } catch (FlushNotAllowedEngineException e) {
                            // ignore
                            continue;
                        } catch (Exception e) {
                            logger.warn("failed to set shard {} index buffer to [{}]", indexShard.shardId(), shardIndexingBufferSize);
                        }
                    }
                }
            }
        }
    }

    private static enum ShardStatusChangeType {
        ADDED, DELETED, BECAME_ACTIVE, BECAME_INACTIVE
    }

    private static class ShardIndexingStatus {
        long translogId = -1;
        boolean activeIndexing = true;
        long timeMS = -1; // contains the first time we saw this shard with no operations done on it
    }
}
