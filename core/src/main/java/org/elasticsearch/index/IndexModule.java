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

package org.elasticsearch.index;

import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.EngineFactory;
import org.elasticsearch.index.engine.InternalEngineFactory;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.index.shard.IndexSearcherWrapper;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 *
 */
public class IndexModule extends AbstractModule {

    private final IndexMetaData indexMetaData;
    private final Settings settings;
    // pkg private so tests can mock
    Class<? extends EngineFactory> engineFactoryImpl = InternalEngineFactory.class;
    Class<? extends IndexSearcherWrapper> indexSearcherWrapper = null;
    private final Set<IndexEventListener> indexEventListeners = new HashSet<>();
    private IndexEventListener listener;


    public IndexModule(Settings settings, IndexMetaData indexMetaData) {
        this.indexMetaData = indexMetaData;
        this.settings = settings;
    }

    public Settings getIndexSettings() {
        return settings;
    }

    public void addIndexEventListener(IndexEventListener listener) {
        if (this.listener != null) {
            throw new IllegalStateException("can't add listener after listeners are frozen");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        if (indexEventListeners.contains(listener)) {
            throw new IllegalArgumentException("listener already added");
        }

        this.indexEventListeners.add(listener);
    }

    public IndexEventListener freeze() {
        // TODO somehow we need to make this pkg private...
        if (listener == null) {
            listener = new CompositeIndexEventListener(indexMetaData.getIndex(), settings, indexEventListeners);
        }
        return listener;
    }

    @Override
    protected void configure() {
        bind(EngineFactory.class).to(engineFactoryImpl).asEagerSingleton();
        if (indexSearcherWrapper == null) {
            bind(IndexSearcherWrapper.class).toProvider(Providers.of(null));
        } else {
            bind(IndexSearcherWrapper.class).to(indexSearcherWrapper).asEagerSingleton();
        }
        bind(IndexEventListener.class).toInstance(freeze());
        bind(IndexMetaData.class).toInstance(indexMetaData);
        bind(IndexService.class).asEagerSingleton();
        bind(IndexServicesProvider.class).asEagerSingleton();
        bind(MapperService.class).asEagerSingleton();
        bind(IndexFieldDataService.class).asEagerSingleton();
    }
}
