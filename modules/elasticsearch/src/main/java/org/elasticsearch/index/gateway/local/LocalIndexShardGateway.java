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

package org.elasticsearch.index.gateway.local;

import org.apache.lucene.index.IndexReader;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.InputStreamStreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.gateway.none.NoneGateway;
import org.elasticsearch.index.gateway.IndexShardGateway;
import org.elasticsearch.index.gateway.IndexShardGatewayRecoveryException;
import org.elasticsearch.index.gateway.RecoveryStatus;
import org.elasticsearch.index.gateway.SnapshotStatus;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.index.shard.AbstractIndexShardComponent;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.index.shard.service.InternalIndexShard;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.index.translog.TranslogStreams;
import org.elasticsearch.index.translog.fs.FsTranslog;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * @author kimchy (shay.banon)
 */
public class LocalIndexShardGateway extends AbstractIndexShardComponent implements IndexShardGateway {

    private final InternalIndexShard indexShard;

    private final RecoveryStatus recoveryStatus = new RecoveryStatus();

    @Inject public LocalIndexShardGateway(ShardId shardId, @IndexSettings Settings indexSettings, IndexShard indexShard) {
        super(shardId, indexSettings);
        this.indexShard = (InternalIndexShard) indexShard;
    }

    @Override public String toString() {
        return "local";
    }

    @Override public RecoveryStatus recoveryStatus() {
        return recoveryStatus;
    }

    @Override public void recover(RecoveryStatus recoveryStatus) throws IndexShardGatewayRecoveryException {
        recoveryStatus().index().startTime(System.currentTimeMillis());
        // read the gateway data persisted
        long version = -1;
        try {
            if (IndexReader.indexExists(indexShard.store().directory())) {
                version = IndexReader.getCurrentVersion(indexShard.store().directory());
            }
        } catch (IOException e) {
            throw new IndexShardGatewayRecoveryException(shardId(), "Failed to fetch index version after copying it over", e);
        }
        recoveryStatus.index().updateVersion(version);
        recoveryStatus.index().time(System.currentTimeMillis() - recoveryStatus.index().startTime());

        recoveryStatus.translog().startTime(System.currentTimeMillis());
        if (version == -1) {
            // no translog files, bail
            indexShard.start();
            // no index, just start the shard and bail
            recoveryStatus.translog().time(System.currentTimeMillis() - recoveryStatus.index().startTime());
            return;
        }

        // move an existing translog, if exists, to "recovering" state, and start reading from it
        FsTranslog translog = (FsTranslog) indexShard.translog();
        File recoveringTranslogFile = new File(translog.location(), "translog-" + version + ".recovering");
        if (!recoveringTranslogFile.exists()) {
            File translogFile = new File(translog.location(), "translog-" + version);
            if (translogFile.exists()) {
                for (int i = 0; i < 3; i++) {
                    if (translogFile.renameTo(recoveringTranslogFile)) {
                        break;
                    }
                }
            }
        }

        if (!recoveringTranslogFile.exists()) {
            // no translog to recovery from, start and bail
            // no translog files, bail
            indexShard.start();
            // no index, just start the shard and bail
            recoveryStatus.translog().time(System.currentTimeMillis() - recoveryStatus.index().startTime());
            return;
        }

        // recover from the translog file
        indexShard.performRecoveryPrepareForTranslog();
        try {
            InputStreamStreamInput si = new InputStreamStreamInput(new FileInputStream(recoveringTranslogFile));
            while (true) {
                int opSize = si.readInt();
                Translog.Operation operation = TranslogStreams.readTranslogOperation(si);
                recoveryStatus.translog().addTranslogOperations(1);
                indexShard.performRecoveryOperation(operation);
            }
        } catch (EOFException e) {
            // ignore this exception, its fine
        } catch (IOException e) {
            // ignore this as well
        }
        indexShard.performRecoveryFinalization(true);

        recoveringTranslogFile.delete();

        recoveryStatus.translog().time(System.currentTimeMillis() - recoveryStatus.index().startTime());
    }

    @Override public String type() {
        return NoneGateway.TYPE;
    }

    @Override public SnapshotStatus snapshot(Snapshot snapshot) {
        return null;
    }

    @Override public SnapshotStatus lastSnapshotStatus() {
        return null;
    }

    @Override public SnapshotStatus currentSnapshotStatus() {
        return null;
    }

    @Override public boolean requiresSnapshotScheduling() {
        return false;
    }

    @Override public void close(boolean delete) {
    }
}
