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

package org.elasticsearch.index.shard;

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.search.IndexSearcher;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.engine.EngineConfig;
import org.elasticsearch.index.engine.EngineException;

import java.io.IOException;

/**
 * Extension point to add custom functionality at request time to the {@link DirectoryReader}
 * and {@link IndexSearcher} managed by the {@link Engine}.
 */
public interface IndexSearcherWrapper {

    /**
     * @param reader The provided directory reader to be wrapped to add custom functionality
     * @return a new directory reader wrapping the provided directory reader or if no wrapping was performed
     *         the provided directory reader
     */
    DirectoryReader wrap(DirectoryReader reader) throws IOException;

    /**
     * @param engineConfig  The engine config which can be used to get the query cache and query cache policy from
     *                      when creating a new index searcher
     * @param searcher      The provided index searcher to be wrapped to add custom functionality
     * @return a new index searcher wrapping the provided index searcher or if no wrapping was performed
     *         the provided index searcher
     */
    IndexSearcher wrap(EngineConfig engineConfig, IndexSearcher searcher) throws IOException;

    /**
     * If there are configured {@link IndexSearcherWrapper} instances, the {@link IndexSearcher} of the provided engine searcher
     * gets wrapped and a new {@link Engine.Searcher} instances is returned, otherwise the provided {@link Engine.Searcher} is returned.
     *
     * This is invoked each time a {@link Engine.Searcher} is requested to do an operation. (for example search)
     */
    default Engine.Searcher wrap(EngineConfig engineConfig, Engine.Searcher engineSearcher) throws IOException {
        DirectoryReader reader = wrap((DirectoryReader) engineSearcher.reader());
        IndexSearcher innerIndexSearcher = new IndexSearcher(reader);
        innerIndexSearcher.setQueryCache(engineConfig.getQueryCache());
        innerIndexSearcher.setQueryCachingPolicy(engineConfig.getQueryCachingPolicy());
        innerIndexSearcher.setSimilarity(engineConfig.getSimilarity());
        // TODO: Right now IndexSearcher isn't wrapper friendly, when it becomes wrapper friendly we should revise this extension point
        // For example if IndexSearcher#rewrite() is overwritten than also IndexSearcher#createNormalizedWeight needs to be overwritten
        // This needs to be fixed before we can allow the IndexSearcher from Engine to be wrapped multiple times
        IndexSearcher indexSearcher = wrap(engineConfig, innerIndexSearcher);
        if (reader == engineSearcher.reader() && indexSearcher == innerIndexSearcher) {
            return engineSearcher;
        } else {
            return new Engine.Searcher(engineSearcher.source(), indexSearcher) {
                @Override
                public void close() throws ElasticsearchException {
                    engineSearcher.close();
                }
            };
        }
    }

}
