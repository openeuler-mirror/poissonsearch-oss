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

package org.elasticsearch.indices.cache.filter;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.ImmutableMap;
import gnu.trove.set.hash.THashSet;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.docset.DocSet;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.index.cache.filter.weighted.WeightedFilterCache;
import org.elasticsearch.monitor.jvm.JvmInfo;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class IndicesFilterCache extends AbstractComponent implements RemovalListener<WeightedFilterCache.FilterCacheKey, DocSet> {

    private final ThreadPool threadPool;

    private Cache<WeightedFilterCache.FilterCacheKey, DocSet> cache;

    private volatile String size;
    private volatile long sizeInBytes;
    private volatile TimeValue expire;

    private final TimeValue cleanInterval;

    private final Set<Object> readersKeysToClean = ConcurrentCollections.newConcurrentSet();

    private volatile boolean closed;

    private volatile Map<String, RemovalListener<WeightedFilterCache.FilterCacheKey, DocSet>> removalListeners =
            ImmutableMap.of();


    static {
        MetaData.addDynamicSettings(
                "indices.cache.filter.size",
                "indices.cache.filter.expire"
        );
    }

    class ApplySettings implements NodeSettingsService.Listener {
        @Override
        public void onRefreshSettings(Settings settings) {
            boolean replace = false;
            String size = settings.get("indices.cache.filter.size", IndicesFilterCache.this.size);
            if (!size.equals(IndicesFilterCache.this.size)) {
                logger.info("updating [indices.cache.filter.size] from [{}] to [{}]", IndicesFilterCache.this.size, size);
                IndicesFilterCache.this.size = size;
                replace = true;
            }
            TimeValue expire = settings.getAsTime("indices.cache.filter.expire", IndicesFilterCache.this.expire);
            if (!Objects.equal(expire, IndicesFilterCache.this.expire)) {
                logger.info("updating [indices.cache.filter.expire] from [{}] to [{}]", IndicesFilterCache.this.expire, expire);
                IndicesFilterCache.this.expire = expire;
                replace = true;
            }
            if (replace) {
                Cache<WeightedFilterCache.FilterCacheKey, DocSet> oldCache = IndicesFilterCache.this.cache;
                computeSizeInBytes();
                buildCache();
                oldCache.invalidateAll();
            }
        }
    }

    @Inject
    public IndicesFilterCache(Settings settings, ThreadPool threadPool, NodeSettingsService nodeSettingsService) {
        super(settings);
        this.threadPool = threadPool;
        this.size = componentSettings.get("size", "20%");
        this.expire = componentSettings.getAsTime("expire", null);
        this.cleanInterval = componentSettings.getAsTime("clean_interval", TimeValue.timeValueSeconds(1));
        computeSizeInBytes();
        buildCache();
        logger.debug("using [node] filter cache with size [{}], actual_size [{}]", size, new ByteSizeValue(sizeInBytes));

        nodeSettingsService.addListener(new ApplySettings());

        threadPool.schedule(cleanInterval, ThreadPool.Names.SAME, new ReaderCleaner());
    }

    private void buildCache() {
        CacheBuilder<WeightedFilterCache.FilterCacheKey, DocSet> cacheBuilder = CacheBuilder.newBuilder()
                .removalListener(this)
                .maximumWeight(sizeInBytes).weigher(new WeightedFilterCache.FilterCacheValueWeigher());

        // defaults to 4, but this is a busy map for all indices, increase it a bit
        cacheBuilder.concurrencyLevel(16);

        if (expire != null) {
            cacheBuilder.expireAfterAccess(expire.millis(), TimeUnit.MILLISECONDS);
        }

        cache = cacheBuilder.build();
    }

    private void computeSizeInBytes() {
        if (size.endsWith("%")) {
            double percent = Double.parseDouble(size.substring(0, size.length() - 1));
            sizeInBytes = (long) ((percent / 100) * JvmInfo.jvmInfo().getMem().getHeapMax().bytes());
        } else {
            sizeInBytes = ByteSizeValue.parseBytesSizeValue(size).bytes();
        }
    }

    public synchronized void addRemovalListener(String index, RemovalListener<WeightedFilterCache.FilterCacheKey, DocSet> listener) {
        removalListeners = MapBuilder.newMapBuilder(removalListeners).put(index, listener).immutableMap();
    }

    public synchronized void removeRemovalListener(String index) {
        removalListeners = MapBuilder.newMapBuilder(removalListeners).remove(index).immutableMap();
    }

    public void addReaderKeyToClean(Object readerKey) {
        readersKeysToClean.add(readerKey);
    }

    public void close() {
        closed = true;
        cache.invalidateAll();
    }

    public Cache<WeightedFilterCache.FilterCacheKey, DocSet> cache() {
        return this.cache;
    }

    @Override
    public void onRemoval(RemovalNotification<WeightedFilterCache.FilterCacheKey, DocSet> removalNotification) {
        WeightedFilterCache.FilterCacheKey key = removalNotification.getKey();
        if (key == null) {
            return;
        }
        RemovalListener<WeightedFilterCache.FilterCacheKey, DocSet> listener = removalListeners.get(key.index());
        if (listener != null) {
            listener.onRemoval(removalNotification);
        }
    }

    /**
     * The reason we need this class ie because we need to clean all the filters that are associated
     * with a reader. We don't want to do it every time a reader closes, since iterating over all the map
     * is expensive. There doesn't seem to be a nicer way to do it (and maintaining a list per reader
     * of the filters will cost more).
     */
    class ReaderCleaner implements Runnable {

        @Override
        public void run() {
            if (closed) {
                return;
            }
            if (readersKeysToClean.isEmpty()) {
                threadPool.schedule(cleanInterval, ThreadPool.Names.SAME, this);
                return;
            }
            threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                @Override
                public void run() {
                    THashSet<Object> keys = CacheRecycler.popHashSet();
                    try {
                        for (Iterator<Object> it = readersKeysToClean.iterator(); it.hasNext(); ) {
                            keys.add(it.next());
                            it.remove();
                        }
                        cache.cleanUp();
                        if (!keys.isEmpty()) {
                            for (Iterator<WeightedFilterCache.FilterCacheKey> it = cache.asMap().keySet().iterator(); it.hasNext(); ) {
                                WeightedFilterCache.FilterCacheKey filterCacheKey = it.next();
                                if (keys.contains(filterCacheKey.readerKey())) {
                                    // same as invalidate
                                    it.remove();
                                }
                            }
                        }
                        threadPool.schedule(cleanInterval, ThreadPool.Names.SAME, ReaderCleaner.this);
                    } finally {
                        CacheRecycler.pushHashSet(keys);
                    }
                }
            });
        }
    }
}