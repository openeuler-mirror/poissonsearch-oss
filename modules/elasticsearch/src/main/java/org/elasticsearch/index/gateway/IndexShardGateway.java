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

package org.elasticsearch.index.gateway;

import org.elasticsearch.ElasticSearchIllegalStateException;
import org.elasticsearch.common.component.CloseableIndexComponent;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.deletionpolicy.SnapshotIndexCommit;
import org.elasticsearch.index.shard.IndexShardComponent;
import org.elasticsearch.index.translog.Translog;

import static org.elasticsearch.common.unit.TimeValue.*;

/**
 * @author kimchy (shay.banon)
 */
public interface IndexShardGateway extends IndexShardComponent, CloseableIndexComponent {

    String type();

    /**
     * Recovers the state of the shard from the gateway.
     */
    RecoveryStatus recover() throws IndexShardGatewayRecoveryException;

    /**
     * Snapshots the given shard into the gateway.
     */
    SnapshotStatus snapshot(Snapshot snapshot) throws IndexShardGatewaySnapshotFailedException;

    /**
     * Returns <tt>true</tt> if this gateway requires scheduling management for snapshot
     * operations.
     */
    boolean requiresSnapshotScheduling();

    public static class Snapshot {
        private final SnapshotIndexCommit indexCommit;
        private final Translog.Snapshot translogSnapshot;

        private final long lastIndexVersion;
        private final long lastTranslogId;
        private final long lastTranslogLength;

        public Snapshot(SnapshotIndexCommit indexCommit, Translog.Snapshot translogSnapshot, long lastIndexVersion, long lastTranslogId, long lastTranslogLength) {
            this.indexCommit = indexCommit;
            this.translogSnapshot = translogSnapshot;
            this.lastIndexVersion = lastIndexVersion;
            this.lastTranslogId = lastTranslogId;
            this.lastTranslogLength = lastTranslogLength;
        }

        /**
         * Indicates that the index has changed from the latest snapshot.
         */
        public boolean indexChanged() {
            return lastIndexVersion != indexCommit.getVersion();
        }

        /**
         * Indicates that a new transaction log has been created. Note check this <b>before</b> you
         * check {@link #sameTranslogNewOperations()}.
         */
        public boolean newTranslogCreated() {
            return translogSnapshot.translogId() != lastTranslogId;
        }

        /**
         * Indicates that the same translog exists, but new operations have been appended to it. Throws
         * {@link ElasticSearchIllegalStateException} if {@link #newTranslogCreated()} is <tt>true</tt>, so
         * always check that first.
         */
        public boolean sameTranslogNewOperations() {
            if (newTranslogCreated()) {
                throw new ElasticSearchIllegalStateException("Should not be called when there is a new translog");
            }
            return translogSnapshot.length() > lastTranslogLength;
        }

        public SnapshotIndexCommit indexCommit() {
            return indexCommit;
        }

        public Translog.Snapshot translogSnapshot() {
            return translogSnapshot;
        }

        public long lastIndexVersion() {
            return lastIndexVersion;
        }

        public long lastTranslogId() {
            return lastTranslogId;
        }

        public long lastTranslogPosition() {
            return lastTranslogLength;
        }
    }

    class SnapshotStatus {

        public static SnapshotStatus NA = new SnapshotStatus(timeValueMillis(0), new Index(0, new ByteSizeValue(0), timeValueMillis(0)), new Translog(0, timeValueMillis(0)));

        private TimeValue totalTime;

        private Index index;

        private Translog translog;

        public SnapshotStatus(TimeValue totalTime, Index index, Translog translog) {
            this.index = index;
            this.translog = translog;
            this.totalTime = totalTime;
        }

        public TimeValue totalTime() {
            return this.totalTime;
        }

        public Index index() {
            return index;
        }

        public Translog translog() {
            return translog;
        }

        public static class Translog {
            private int numberOfOperations;
            private TimeValue time;

            public Translog(int numberOfOperations, TimeValue time) {
                this.numberOfOperations = numberOfOperations;
                this.time = time;
            }

            public int numberOfOperations() {
                return numberOfOperations;
            }

            public TimeValue time() {
                return time;
            }
        }

        public static class Index {
            private int numberOfFiles;
            private ByteSizeValue totalSize;
            private TimeValue time;

            public Index(int numberOfFiles, ByteSizeValue totalSize, TimeValue time) {
                this.numberOfFiles = numberOfFiles;
                this.totalSize = totalSize;
                this.time = time;
            }

            public TimeValue time() {
                return this.time;
            }

            public int numberOfFiles() {
                return numberOfFiles;
            }

            public ByteSizeValue totalSize() {
                return totalSize;
            }
        }
    }

    class RecoveryStatus {

        private Index index;

        private Translog translog;

        public RecoveryStatus(Index index, Translog translog) {
            this.index = index;
            this.translog = translog;
        }

        public Index index() {
            return index;
        }

        public Translog translog() {
            return translog;
        }

        public static class Translog {
            private long translogId;
            private int numberOfOperations;
            private ByteSizeValue totalSize;
            private long translogLength;

            public Translog(long translogId, long translogLength, int numberOfOperations, ByteSizeValue totalSize) {
                this.translogId = translogId;
                this.translogLength = translogLength;
                this.numberOfOperations = numberOfOperations;
                this.totalSize = totalSize;
            }

            /**
             * The translog id recovered, <tt>-1</tt> indicating no translog.
             */
            public long translogId() {
                return translogId;
            }

            public long translogLength() {
                return translogLength;
            }

            public int numberOfOperations() {
                return numberOfOperations;
            }

            public ByteSizeValue totalSize() {
                return totalSize;
            }
        }

        public static class Index {
            public static final Index EMPTY = new Index(-1, 0, new ByteSizeValue(0), 0, new ByteSizeValue(0), timeValueMillis(0));

            private long version;
            private int numberOfFiles;
            private ByteSizeValue totalSize;
            private int numberOfExistingFiles;
            private ByteSizeValue existingTotalSize;
            private TimeValue throttlingWaitTime;

            public Index(long version, int numberOfFiles, ByteSizeValue totalSize,
                         int numberOfExistingFiles, ByteSizeValue existingTotalSize,
                         TimeValue throttlingWaitTime) {
                this.version = version;
                this.numberOfFiles = numberOfFiles;
                this.totalSize = totalSize;
                this.numberOfExistingFiles = numberOfExistingFiles;
                this.existingTotalSize = existingTotalSize;
                this.throttlingWaitTime = throttlingWaitTime;
            }

            public long version() {
                return this.version;
            }

            public int numberOfFiles() {
                return numberOfFiles;
            }

            public ByteSizeValue totalSize() {
                return totalSize;
            }

            public int numberOfExistingFiles() {
                return numberOfExistingFiles;
            }

            public ByteSizeValue existingTotalSize() {
                return existingTotalSize;
            }

            public TimeValue throttlingWaitTime() {
                return throttlingWaitTime;
            }
        }
    }
}
