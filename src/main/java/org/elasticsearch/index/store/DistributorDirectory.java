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
package org.elasticsearch.index.store;

import org.apache.lucene.store.*;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.math.MathUtils;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.store.distributor.Distributor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collection;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A directory implementation that uses the Elasticsearch {@link Distributor} abstraction to distribute
 * files across multiple data directories.
 */
public final class DistributorDirectory extends BaseDirectory {

    private final Distributor distributor;
    private final ConcurrentMap<String, Directory> nameDirMapping = ConcurrentCollections.newConcurrentMap();

    /**
     * Creates a new DistributorDirectory from multiple directories. Note: The first directory in the given array
     * is used as the primary directory holding the file locks as well as the SEGMENTS_GEN file. All remaining
     * directories are used in a round robin fashion.
     */
    public DistributorDirectory(final Directory... dirs) throws IOException {
        this(new Distributor() {
            final AtomicInteger count = new AtomicInteger();

            @Override
            public Directory primary() {
                return dirs[0];
            }

            @Override
            public Directory[] all() {
                return dirs;
            }

            @Override
            public synchronized Directory any() {
                return dirs[MathUtils.mod(count.incrementAndGet(), dirs.length)];
            }
        });
    }

    /**
     * Creates a new DistributorDirectory form the given Distributor.
     */
    public DistributorDirectory(Distributor distributor) throws IOException {
        this.distributor = distributor;
        for (Directory dir : distributor.all()) {
            for (String file : dir.listAll()) {
                nameDirMapping.put(file, dir);
            }
        }
        lockFactory = new DistributorLockFactoryWrapper(distributor.primary());
    }

    @Override
    public final String[] listAll() throws IOException {
        return nameDirMapping.keySet().toArray(new String[0]);
    }

    @Override
    public boolean fileExists(String name) throws IOException {
        try {
            return getDirectory(name).fileExists(name);
        } catch (FileNotFoundException ex) {
            return false;
        }
    }

    @Override
    public void deleteFile(String name) throws IOException {
        getDirectory(name, true).deleteFile(name);
        Directory remove = nameDirMapping.remove(name);
        assert remove != null : "Tried to delete file " + name + " but couldn't";
    }

    @Override
    public long fileLength(String name) throws IOException {
        return getDirectory(name).fileLength(name);
    }

    @Override
    public IndexOutput createOutput(String name, IOContext context) throws IOException {
        return getDirectory(name, false).createOutput(name, context);
    }

    @Override
    public void sync(Collection<String> names) throws IOException {
        for (Directory dir : distributor.all()) {
            dir.sync(names);
        }
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        return getDirectory(name).openInput(name, context);
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(distributor.all());
    }

    /**
     * Returns the directory that has previously been associated with this file name.
     *
     * @throws IOException if the name has not yet been associated with any directory ie. fi the file does not exists
     */
    private Directory getDirectory(String name) throws IOException {
        return getDirectory(name, true);
    }

    /**
     * Returns the directory that has previously been associated with this file name or associates the name with a directory
     * if failIfNotAssociated is set to false.
     */
    private Directory getDirectory(String name, boolean failIfNotAssociated) throws IOException {
        Directory directory = nameDirMapping.get(name);
        if (directory == null) {
            if (failIfNotAssociated) {
                throw new FileNotFoundException("No such file [" + name + "]");
            }
            // Pick a directory and associate this new file with it:
            final Directory dir = distributor.any();
            directory = nameDirMapping.putIfAbsent(name, dir);
            if (directory == null) {
                // putIfAbsent did in fact put dir:
                directory = dir;
            }
        }
            
        return directory;
    }

    @Override
    public void setLockFactory(LockFactory lockFactory) throws IOException {
        distributor.primary().setLockFactory(lockFactory);
        super.setLockFactory(new DistributorLockFactoryWrapper(distributor.primary()));
    }

    @Override
    public String getLockID() {
        return distributor.primary().getLockID();
    }

    @Override
    public String toString() {
        return distributor.toString();
    }

    /**
     * Renames the given source file to the given target file unless the target already exists.
     *
     * @param directoryService the DirectoryService to use.
     * @param from the source file name.
     * @param to the target file name
     * @throws IOException if the target file already exists.
     */
    public void renameFile(DirectoryService directoryService, String from, String to) throws IOException {
        Directory directory = getDirectory(from);
        if (nameDirMapping.putIfAbsent(to, directory) != null) {
            throw new IOException("Can't rename file from " + from
                    + " to: " + to + ": target file already exists");
        }
        boolean success = false;
        try {
            directoryService.renameFile(directory, from, to);
            nameDirMapping.remove(from);
            success = true;
        } finally {
            if (!success) {
                nameDirMapping.remove(to);
            }
        }
    }

    Distributor getDistributor() {
        return distributor;
    }

    /**
     * Basic checks to ensure the internal mapping is consistent - should only be used in assertions
     */
    static boolean assertConsistency(ESLogger logger, DistributorDirectory dir) throws IOException {
        boolean consistent = true;
        StringBuilder builder = new StringBuilder();
        Directory[] all = dir.distributor.all();
        for (Directory d : all) {
            for (String file : d.listAll()) {
            final Directory directory = dir.nameDirMapping.get(file);
                if (directory == null) {
                    consistent = false;
                    builder.append("File ").append(file)
                            .append(" was not mapped to a directory but exists in one of the distributors directories")
                            .append(System.lineSeparator());
                }
                if (directory != d) {
                    consistent = false;
                    builder.append("File ").append(file).append(" was  mapped to a directory ").append(directory)
                            .append(" but exists in another distributor directory").append(d)
                            .append(System.lineSeparator());
                }

            }
        }
        if (consistent == false) {
            logger.info(builder.toString());
        }
        assert consistent: builder.toString();
        return consistent; // return boolean so it can be easily be used in asserts
    }

    /**
     * This inner class is a simple wrapper around the original
     * lock factory to track files written / created through the
     * lock factory. For instance {@link NativeFSLockFactory} creates real
     * files that we should expose for consistency reasons.
     */
    private class DistributorLockFactoryWrapper extends LockFactory {
        private final Directory dir;
        private final LockFactory delegate;
        private final boolean writesFiles;

        public DistributorLockFactoryWrapper(Directory dir) {
            this.dir = dir;
            final FSDirectory leaf = DirectoryUtils.getLeaf(dir, FSDirectory.class);
            if (leaf != null) {
               writesFiles = leaf.getLockFactory() instanceof FSLockFactory;
            } else {
                writesFiles = false;
            }
            this.delegate = dir.getLockFactory();
        }

        @Override
        public void setLockPrefix(String lockPrefix) {
            delegate.setLockPrefix(lockPrefix);
        }

        @Override
        public String getLockPrefix() {
            return delegate.getLockPrefix();
        }

        @Override
        public Lock makeLock(String lockName) {
            return new DistributorLock(delegate.makeLock(lockName), lockName);
        }

        @Override
        public void clearLock(String lockName) throws IOException {
            delegate.clearLock(lockName);
        }

        @Override
        public String toString() {
            return "DistributorLockFactoryWrapper(" + delegate.toString() + ")";
        }

        private class DistributorLock extends Lock {
            private final Lock delegateLock;
            private final String name;

            DistributorLock(Lock delegate, String name) {
                this.delegateLock = delegate;
                this.name = name;
            }

            @Override
            public boolean obtain() throws IOException {
                if (delegateLock.obtain()) {
                    if (writesFiles) {
                        assert (nameDirMapping.containsKey(name) == false || nameDirMapping.get(name) == dir);
                        nameDirMapping.putIfAbsent(name, dir);
                    }
                    return true;
                } else {
                    return false;
                }
            }

            @Override
            public void close() throws IOException { delegateLock.close(); }

            @Override
            public boolean isLocked() throws IOException {
                return delegateLock.isLocked();
            }
        }
    }
}