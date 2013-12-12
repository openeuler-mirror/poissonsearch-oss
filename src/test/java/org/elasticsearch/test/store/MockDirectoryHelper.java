/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.test.store;

import com.carrotsearch.randomizedtesting.SeedUtils;
import org.apache.lucene.store.*;
import org.apache.lucene.store.MockDirectoryWrapper.Throttling;
import org.apache.lucene.util.Constants;
import org.elasticsearch.cache.memory.ByteBufferCache;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.store.DirectoryService;
import org.elasticsearch.index.store.IndexStore;
import org.elasticsearch.index.store.fs.FsDirectoryService;
import org.elasticsearch.index.store.fs.MmapFsDirectoryService;
import org.elasticsearch.index.store.fs.NioFsDirectoryService;
import org.elasticsearch.index.store.fs.SimpleFsDirectoryService;
import org.elasticsearch.index.store.memory.ByteBufferDirectoryService;
import org.elasticsearch.index.store.ram.RamDirectoryService;
import org.elasticsearch.test.ElasticsearchIntegrationTest;

import java.io.IOException;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

public class MockDirectoryHelper {
    public static final String RANDOM_IO_EXCEPTION_RATE = "index.store.mock.random.io_exception_rate";
    public static final String RANDOM_IO_EXCEPTION_RATE_ON_OPEN = "index.store.mock.random.io_exception_rate_on_open";
    public static final String RANDOM_THROTTLE = "index.store.mock.random.throttle";
    public static final String CHECK_INDEX_ON_CLOSE = "index.store.mock.check_index_on_close";
    public static final String RANDOM_PREVENT_DOUBLE_WRITE = "index.store.mock.random.prevent_double_write";
    public static final String RANDOM_NO_DELETE_OPEN_FILE = "index.store.mock.random.no_delete_open_file";
    public static final String RANDOM_FAIL_ON_CLOSE= "index.store.mock.random.fail_on_close";

    public static final Set<ElasticsearchMockDirectoryWrapper> wrappers = ConcurrentCollections.newConcurrentSet();
    
    private final Random random;
    private final double randomIOExceptionRate;
    private final double randomIOExceptionRateOnOpen;
    private final Throttling throttle;
    private final boolean checkIndexOnClose;
    private final Settings indexSettings;
    private final ShardId shardId;
    private final boolean preventDoubleWrite;
    private final boolean noDeleteOpenFile;
    private final ESLogger logger;
    private final boolean failOnClose;

    public MockDirectoryHelper(ShardId shardId, Settings indexSettings, ESLogger logger) {
        final long seed = indexSettings.getAsLong(ElasticsearchIntegrationTest.INDEX_SEED_SETTING, 0l);
        random = new Random(seed);
        randomIOExceptionRate = indexSettings.getAsDouble(RANDOM_IO_EXCEPTION_RATE, 0.0d);
        randomIOExceptionRateOnOpen = indexSettings.getAsDouble(RANDOM_IO_EXCEPTION_RATE_ON_OPEN, 0.0d);
        preventDoubleWrite = indexSettings.getAsBoolean(RANDOM_PREVENT_DOUBLE_WRITE, true); // true is default in MDW
        noDeleteOpenFile = indexSettings.getAsBoolean(RANDOM_NO_DELETE_OPEN_FILE, random.nextBoolean()); // true is default in MDW
        random.nextInt(shardId.getId() + 1); // some randomness per shard
        throttle = Throttling.valueOf(indexSettings.get(RANDOM_THROTTLE, random.nextDouble() < 0.1 ? "SOMETIMES" : "NEVER"));
        checkIndexOnClose = indexSettings.getAsBoolean(CHECK_INDEX_ON_CLOSE, random.nextDouble() < 0.1);
        failOnClose = indexSettings.getAsBoolean(RANDOM_FAIL_ON_CLOSE, false);

        if (logger.isDebugEnabled()) {
            logger.debug("Using MockDirWrapper with seed [{}] throttle: [{}] checkIndexOnClose: [{}]", SeedUtils.formatSeed(seed),
                    throttle, checkIndexOnClose);
        }
        this.indexSettings = indexSettings;
        this.shardId = shardId;
        this.logger = logger;
    }

    public Directory wrap(Directory dir) {
        final ElasticsearchMockDirectoryWrapper w = new ElasticsearchMockDirectoryWrapper(random, dir, logger, failOnClose);
        w.setRandomIOExceptionRate(randomIOExceptionRate);
        w.setRandomIOExceptionRateOnOpen(randomIOExceptionRateOnOpen);
        w.setThrottling(throttle);
        w.setCheckIndexOnClose(checkIndexOnClose);
        w.setPreventDoubleWrite(preventDoubleWrite);
        w.setNoDeleteOpenFile(noDeleteOpenFile);
        wrappers.add(w);
        return w;
    }

    public Directory[] wrapAllInplace(Directory[] dirs) {
        for (int i = 0; i < dirs.length; i++) {
            dirs[i] = wrap(dirs[i]);
        }
        return dirs;
    }

    public FsDirectoryService randomDirectorService(IndexStore indexStore) {
        if ((Constants.WINDOWS || Constants.SUN_OS) && Constants.JRE_IS_64BIT && MMapDirectory.UNMAP_SUPPORTED) {
            return new MmapFsDirectoryService(shardId, indexSettings, indexStore);
        } else if (Constants.WINDOWS) {
            return new SimpleFsDirectoryService(shardId, indexSettings, indexStore);
        }
        switch (random.nextInt(3)) {
        case 1:
            return new MmapFsDirectoryService(shardId, indexSettings, indexStore);
        case 0:
            return new SimpleFsDirectoryService(shardId, indexSettings, indexStore);
        default:
            return new NioFsDirectoryService(shardId, indexSettings, indexStore);
        }
    }

    public DirectoryService randomRamDirecoryService(ByteBufferCache byteBufferCache) {
        switch (random.nextInt(2)) {
        case 0:
            return new RamDirectoryService(shardId, indexSettings);
        default:
            return new ByteBufferDirectoryService(shardId, indexSettings, byteBufferCache);
        }

    }

    public static final class ElasticsearchMockDirectoryWrapper extends MockDirectoryWrapper {

        private final ESLogger logger;
        private final boolean failOnClose;

        public ElasticsearchMockDirectoryWrapper(Random random, Directory delegate, ESLogger logger, boolean failOnClose) {
            super(random, delegate);
            this.logger = logger;
            this.failOnClose = failOnClose;
        }

        @Override
        public  void close() throws IOException {
            try {
                super.close();
            } catch (RuntimeException ex) {
                if (failOnClose) {
                    throw ex;
                }
                // we catch the exception on close to properly close shards even if there are open files
                // the test framework will call closeWithRuntimeException after the test exits to fail
                // on unclosed files.
                logger.debug("MockDirectoryWrapper#close() threw exception", ex);
            }
        }

        public void closeWithRuntimeException() throws IOException {
            super.close(); // force fail if open files etc. called in tear down of ElasticsearchIntegrationTest
        }

        @Override
        public synchronized IndexInput openInput(String name, IOContext context) throws IOException {
            return new CloseTrackingMockIndexInputWrapper(name, super.openInput(name, context), logger);
        }

        @Override
        public IndexInputSlicer createSlicer(final String name, IOContext context) throws IOException {
            final IndexInputSlicer slicer = super.createSlicer(name, context);
            return new IndexInputSlicer() {

                @Override
                public IndexInput openSlice(String sliceDescription, long offset, long length) throws IOException {
                    return new CloseTrackingMockIndexInputWrapper(name, slicer.openSlice(sliceDescription, offset, length), logger);
                }

                @Override
                public IndexInput openFullSlice() throws IOException {
                    return new CloseTrackingMockIndexInputWrapper(name, slicer.openFullSlice(), logger);
                }

                @Override
                public void close() throws IOException {
                    slicer.close();
                }
            };
        }
    }

    private static class CloseTrackingMockIndexInputWrapper extends IndexInput {
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final String name;
        private IndexInput delegate;
        private final ESLogger logger;
        private volatile RuntimeException closingStack;

        public CloseTrackingMockIndexInputWrapper(String name, IndexInput delegate, ESLogger logger) {
            super(name);
            this.delegate = delegate;
            this.logger = logger;
            this.name = name;
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                closingStack = new RuntimeException("IndexInput closed");
                delegate.close();
            }
        }

        @Override
        public IndexInput clone() {
            ensureOpen();
            return delegate.clone();
        }


        private void ensureOpen() {
            if (closed.get()) {
                logger.debug("Abusing IndexInput for: [" + name + "] - already closed", closingStack);
                throw new RuntimeException("Abusing closed IndexInput!");
            }
        }

        @Override
        public long getFilePointer() {
            ensureOpen();
            return delegate.getFilePointer();
        }

        @Override
        public void seek(long pos) throws IOException {
            ensureOpen();
            delegate.seek(pos);
        }

        @Override
        public long length() {
            ensureOpen();
            return delegate.length();
        }

        @Override
        public byte readByte() throws IOException {
            ensureOpen();
            return delegate.readByte();
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            ensureOpen();
            delegate.readBytes(b, offset, len);
        }

        @Override
        public void readBytes(byte[] b, int offset, int len, boolean useBuffer)
                throws IOException {
            ensureOpen();
            delegate.readBytes(b, offset, len, useBuffer);
        }

        @Override
        public short readShort() throws IOException {
            ensureOpen();
            return delegate.readShort();
        }

        @Override
        public int readInt() throws IOException {
            ensureOpen();
            return delegate.readInt();
        }

        @Override
        public long readLong() throws IOException {
            ensureOpen();
            return delegate.readLong();
        }

        @Override
        public String readString() throws IOException {
            ensureOpen();
            return delegate.readString();
        }

        @Override
        public Map<String,String> readStringStringMap() throws IOException {
            ensureOpen();
            return delegate.readStringStringMap();
        }

        @Override
        public int readVInt() throws IOException {
            ensureOpen();
            return delegate.readVInt();
        }

        @Override
        public long readVLong() throws IOException {
            ensureOpen();
            return delegate.readVLong();
        }

        @Override
        public String toString() {
            return "CloseTrackingMockIndexInputWrapper(" + delegate + ")";
        }

    }
}
