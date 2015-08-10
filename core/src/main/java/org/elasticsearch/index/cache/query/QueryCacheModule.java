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

package org.elasticsearch.index.cache.query;

import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.common.Classes;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Scopes;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.cache.query.index.IndexQueryCache;

/**
 *
 */
public class QueryCacheModule extends AbstractModule {

    public static final class QueryCacheSettings {
        public static final String QUERY_CACHE_TYPE = "index.queries.cache.type";
        // for test purposes only
        public static final String QUERY_CACHE_EVERYTHING = "index.queries.cache.everything";
    }

    private final Settings settings;

    public QueryCacheModule(Settings settings) {
        this.settings = settings;
    }

    @Override
    protected void configure() {
        Class<? extends IndexQueryCache> queryCacheClass = IndexQueryCache.class;
        String customQueryCache = settings.get(QueryCacheSettings.QUERY_CACHE_TYPE);
        if (customQueryCache != null) {
            // TODO: make this only useable from tests
            queryCacheClass = Classes.loadClass(getClass().getClassLoader(), customQueryCache);
        }
        bind(QueryCache.class).to(queryCacheClass).in(Scopes.SINGLETON);
    }
}
