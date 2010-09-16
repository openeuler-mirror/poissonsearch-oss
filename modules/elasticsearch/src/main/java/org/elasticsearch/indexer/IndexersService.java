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

package org.elasticsearch.indexer;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.Injectors;
import org.elasticsearch.common.inject.ModulesBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indexer.cluster.IndexerClusterChangedEvent;
import org.elasticsearch.indexer.cluster.IndexerClusterService;
import org.elasticsearch.indexer.cluster.IndexerClusterState;
import org.elasticsearch.indexer.cluster.IndexerClusterStateListener;
import org.elasticsearch.indexer.metadata.IndexerMetaData;
import org.elasticsearch.indexer.routing.IndexerRouting;
import org.elasticsearch.indexer.settings.IndexerSettingsModule;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.elasticsearch.common.settings.ImmutableSettings.*;

/**
 * @author kimchy (shay.banon)
 */
public class IndexersService extends AbstractLifecycleComponent<IndexersService> {

    private final ThreadPool threadPool;

    private final ClusterService clusterService;

    private final Injector injector;

    private final Map<IndexerName, Injector> indexersInjectors = Maps.newHashMap();

    private volatile ImmutableMap<IndexerName, Indexer> indexers = ImmutableMap.of();

    @Inject public IndexersService(Settings settings, ThreadPool threadPool, ClusterService clusterService, IndexerClusterService indexerClusterService, Injector injector) {
        super(settings);
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        this.injector = injector;
        indexerClusterService.add(new ApplyIndexers());
    }

    @Override protected void doStart() throws ElasticSearchException {
    }

    @Override protected void doStop() throws ElasticSearchException {
        ImmutableSet<IndexerName> indices = ImmutableSet.copyOf(this.indexers.keySet());
        final CountDownLatch latch = new CountDownLatch(indices.size());
        for (final IndexerName indexerName : indices) {
            threadPool.cached().execute(new Runnable() {
                @Override public void run() {
                    try {
                        deleteIndexer(indexerName, false);
                    } catch (Exception e) {
                        logger.warn("failed to delete indexer on stop [{}]/[{}]", e, indexerName.type(), indexerName.name());
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            // ignore
        }
    }

    @Override protected void doClose() throws ElasticSearchException {
    }

    public synchronized Indexer createIndexer(IndexerName indexerName, Settings settings) throws ElasticSearchException {
        if (indexersInjectors.containsKey(indexerName)) {
            throw new IndexerException(indexerName, "indexer already exists");
        }

        logger.debug("creating indexer [{}][{}]", indexerName.type(), indexerName.name());

        Settings indexerSettings = settingsBuilder()
                .put(this.settings)
                .put(settings)
                .classLoader(settings.getClassLoader())
                .globalSettings(settings.getGlobalSettings())
                .build();

        ModulesBuilder modules = new ModulesBuilder();
        modules.add(new IndexerNameModule(indexerName));
        modules.add(new IndexerSettingsModule(indexerSettings));
        modules.add(new IndexerModule(indexerName, indexerSettings));

        Injector indexInjector = modules.createChildInjector(injector);
        indexersInjectors.put(indexerName, indexInjector);
        Indexer indexer = indexInjector.getInstance(Indexer.class);
        indexers = MapBuilder.newMapBuilder(indexers).put(indexerName, indexer).immutableMap();
        return indexer;
    }

    public synchronized void cleanIndexer(IndexerName indexerName) throws ElasticSearchException {
        deleteIndexer(indexerName, false);
    }

    public synchronized void deleteIndexer(IndexerName indexerName) throws ElasticSearchException {
        deleteIndexer(indexerName, true);
    }

    private void deleteIndexer(IndexerName indexerName, boolean delete) {
        Injector indexerInjector;
        Indexer indexer;
        synchronized (this) {
            indexerInjector = indexersInjectors.remove(indexerName);
            if (indexerInjector == null) {
                if (!delete) {
                    return;
                }
                throw new IndexerException(indexerName, "missing");
            }
            if (delete) {
                logger.debug("deleting indexer [{}][{}]", indexerName.type(), indexerName.name());
            }

            Map<IndexerName, Indexer> tmpMap = Maps.newHashMap(indexers);
            indexer = tmpMap.remove(indexerName);
            indexers = ImmutableMap.copyOf(tmpMap);
        }

//        for (Class<? extends CloseableIndexerComponent> closeable : pluginsService.indexServices()) {
//            indexerInjector.getInstance(closeable).close(delete);
//        }

        indexer.close(delete);

        indexerInjector.getInstance(Indexer.class).close(delete);

        Injectors.close(injector);
    }

    private class ApplyIndexers implements IndexerClusterStateListener {
        @Override public void indexerClusterChanged(IndexerClusterChangedEvent event) {
            DiscoveryNode localNode = clusterService.localNode();
            IndexerClusterState state = event.state();

            // first, go over and delete ones that either don't exists or are not allocated
            for (IndexerName indexerName : indexers.keySet()) {
                // if its not on the metadata, it was deleted, delete it
                IndexerMetaData indexerMetaData = state.metaData().indexer(indexerName);
                if (indexerMetaData == null) {
                    deleteIndexer(indexerName);
                }

                IndexerRouting routing = state.routing().routing(indexerName);
                if (routing == null || !localNode.equals(routing.node())) {
                    // not routed at all, and not allocated here, clean it (we delete the relevant ones before)
                    cleanIndexer(indexerName);
                }
            }

            for (IndexerRouting routing : state.routing()) {
                // only apply changes to the local node
                if (!routing.node().equals(localNode)) {
                    continue;
                }

                IndexerMetaData indexerMetaData = state.metaData().indexer(routing.indexerName());

                createIndexer(indexerMetaData.indexerName(), indexerMetaData.settings());
            }
        }
    }
}
