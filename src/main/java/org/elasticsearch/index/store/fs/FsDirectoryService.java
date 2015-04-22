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

package org.elasticsearch.index.store.fs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.lucene.store.*;
import org.elasticsearch.common.metrics.CounterMetric;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.ShardPath;
import org.elasticsearch.index.store.DirectoryService;
import org.elasticsearch.index.store.IndexStore;
import org.elasticsearch.index.store.StoreException;

/**
 */
public abstract class FsDirectoryService extends DirectoryService implements StoreRateLimiting.Listener, StoreRateLimiting.Provider {

    protected final IndexStore indexStore;

    private final CounterMetric rateLimitingTimeInNanos = new CounterMetric();
    private final ShardPath path;

    public FsDirectoryService(ShardId shardId, @IndexSettings Settings indexSettings, IndexStore indexStore, ShardPath path) {
        super(shardId, indexSettings);
        this.path = path;
        this.indexStore = indexStore;
    }

    @Override
    public long throttleTimeInNanos() {
        return rateLimitingTimeInNanos.count();
    }

    @Override
    public StoreRateLimiting rateLimiting() {
        return indexStore.rateLimiting();
    }

    protected final LockFactory buildLockFactory() throws IOException {
        String fsLock = indexSettings.get("index.store.fs.lock", indexSettings.get("index.store.fs.fs_lock", "native"));
        LockFactory lockFactory;
        if (fsLock.equals("native")) {
            lockFactory = NativeFSLockFactory.INSTANCE;
        } else if (fsLock.equals("simple")) {
            lockFactory = SimpleFSLockFactory.INSTANCE;
        } else {
            throw new StoreException(shardId, "unrecognized fs_lock \"" + fsLock + "\": must be native or simple");
        }
        return lockFactory;
    }

    @Override
    public Directory newDirectory() throws IOException {
        final Path location = path.resolveIndex();
        Files.createDirectories(location);
        Directory wrapped = newFSDirectory(location, buildLockFactory());
        return new RateLimitedFSDirectory(wrapped, this, this) ;
    }

    protected abstract Directory newFSDirectory(Path location, LockFactory lockFactory) throws IOException;

    @Override
    public void onPause(long nanos) {
        rateLimitingTimeInNanos.inc(nanos);
    }
}
