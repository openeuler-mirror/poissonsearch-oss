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

package org.elasticsearch.index.store.support;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.AbstractIndexComponent;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.settings.IndexSettingsService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.IndexStore;
import org.elasticsearch.indices.store.IndicesStore;

import java.io.IOException;
import java.nio.file.Path;

/**
 *
 */
public abstract class AbstractIndexStore extends AbstractIndexComponent implements IndexStore {

    public static final String INDEX_FOLDER_NAME = "index";
    public static final String TRANSLOG_FOLDER_NAME = "translog";
    private final NodeEnvironment nodeEnv;

    private final Path[] locations;

    protected final IndexService indexService;

    protected final IndicesStore indicesStore;

    protected AbstractIndexStore(Index index, @IndexSettings Settings indexSettings, IndexService indexService, IndicesStore indicesStore, NodeEnvironment nodeEnv) {
        super(index, indexSettings);
        this.indexService = indexService;
        this.indicesStore = indicesStore;

        this.nodeEnv = nodeEnv;
        if (nodeEnv.hasNodeFile()) {
            this.locations = nodeEnv.indexPaths(index);
        } else {
            this.locations = null;
        }
    }

    @Override
    public void close() throws ElasticsearchException {
    }

    @Override
    public boolean canDeleteUnallocated(ShardId shardId, @IndexSettings Settings indexSettings) {
        if (locations == null) {
            return false;
        }
        if (indexService.hasShard(shardId.id())) {
            return false;
        }
        return FileSystemUtils.exists(nodeEnv.shardPaths(shardId));
    }

    @Override
    public void deleteUnallocated(ShardId shardId, @IndexSettings Settings indexSettings) throws IOException {
        if (locations == null) {
            return;
        }
        if (indexService.hasShard(shardId.id())) {
            throw new ElasticsearchIllegalStateException(shardId + " allocated, can't be deleted");
        }
        try {
            nodeEnv.deleteShardDirectorySafe(shardId, indexSettings);
        } catch (Exception ex) {
            logger.debug("failed to delete shard locations", ex);
        }
    }

    /**
     * Return an array of all index folder locations for a given shard. Uses
     * the index settings to determine if a custom data path is set for the
     * index and uses that if applicable.
     */
    public Path[] shardIndexLocations(ShardId shardId) {
        Path[] shardLocations = nodeEnv.shardDataPaths(shardId, indexSettings);
        Path[] locations = new Path[shardLocations.length];
        for (int i = 0; i < shardLocations.length; i++) {
            locations[i] = shardLocations[i].resolve(INDEX_FOLDER_NAME);
        }
        logger.debug("using [{}] as shard's index location", locations);
        return locations;
    }

    /**
     * Return an array of all translog folder locations for a given shard. Uses
     * the index settings to determine if a custom data path is set for the
     * index and uses that if applicable.
     */
    public Path[] shardTranslogLocations(ShardId shardId) {
        Path[] shardLocations = nodeEnv.shardDataPaths(shardId, indexSettings);
        Path[] locations = new Path[shardLocations.length];
        for (int i = 0; i < shardLocations.length; i++) {
            locations[i] = shardLocations[i].resolve(TRANSLOG_FOLDER_NAME);
        }
        logger.debug("using [{}] as shard's translog location", locations);
        return locations;
    }
}
