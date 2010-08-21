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

package org.elasticsearch.index.translog;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineClosedException;
import org.elasticsearch.index.engine.FlushNotAllowedEngineException;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.AbstractIndexShardComponent;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.concurrent.ScheduledFuture;

import static org.elasticsearch.common.unit.TimeValue.*;

/**
 * @author kimchy (shay.banon)
 */
public class TranslogService extends AbstractIndexShardComponent {

    private final ThreadPool threadPool;

    private final IndexShard indexShard;

    private final Translog translog;

    private final int flushThreshold;

    private final TimeValue interval;

    private ScheduledFuture future;

    @Inject public TranslogService(ShardId shardId, @IndexSettings Settings indexSettings, ThreadPool threadPool, IndexShard indexShard, Translog translog) {
        super(shardId, indexSettings);
        this.threadPool = threadPool;
        this.indexShard = indexShard;
        this.translog = translog;

        this.flushThreshold = componentSettings.getAsInt("flush_threshold", 5000);
        this.interval = componentSettings.getAsTime("interval", timeValueMillis(1000));

        this.future = threadPool.scheduleWithFixedDelay(new TranslogBasedFlush(), interval);
    }


    public void close() {
        this.future.cancel(true);
    }

    private class TranslogBasedFlush implements Runnable {
        @Override public void run() {
            if (indexShard.state() != IndexShardState.STARTED) {
                return;
            }

            int currentSize = translog.size();
            if (currentSize > flushThreshold) {
                logger.trace("flushing translog, operations [{}], breached [{}]", currentSize, flushThreshold);
                try {
                    indexShard.flush(new Engine.Flush());
                } catch (EngineClosedException e) {
                    // we are being closed, ignore
                } catch (FlushNotAllowedEngineException e) {
                    // ignore this exception, we are not allowed to perform flush
                } catch (Exception e) {
                    logger.warn("failed to flush shard on translog threshold", e);
                }
            }
        }
    }
}
