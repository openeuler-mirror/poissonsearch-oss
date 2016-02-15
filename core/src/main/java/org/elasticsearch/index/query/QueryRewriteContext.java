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
package org.elasticsearch.index.query;

import org.elasticsearch.client.Client;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.script.ScriptService;

/**
 * Context object used to rewrite {@link QueryBuilder} instances into simplified version.
 */
public class QueryRewriteContext {
    protected final ScriptService scriptService;
    protected final IndexSettings indexSettings;
    protected final IndicesQueriesRegistry indicesQueriesRegistry;
    protected final QueryParseContext parseContext;

    public QueryRewriteContext(IndexSettings indexSettings, ScriptService scriptService, IndicesQueriesRegistry indicesQueriesRegistry) {
        this.scriptService = scriptService;
        this.indexSettings = indexSettings;
        this.indicesQueriesRegistry = indicesQueriesRegistry;
        this.parseContext = new QueryParseContext(indicesQueriesRegistry);
    }

    /**
     * Returns a clients to fetch resources from local or remove nodes.
     */
    public final Client getClient() {
        return scriptService.getClient();
    }

    /**
     * Returns the index settings for this context. This might return null if the
     * context has not index scope.
     */
    public final IndexSettings getIndexSettings() {
        return indexSettings;
    }

    /**
     * Returns a script service to fetch scripts.
     */
    public final ScriptService getScriptService() {
        return scriptService;
    }

    /**
     * Returns a new {@link QueryParseContext} to parse template or wrapped queries.
     */
    public QueryParseContext newParseContext() {
        QueryParseContext queryParseContext = new QueryParseContext(indicesQueriesRegistry);
        queryParseContext.parseFieldMatcher(parseContext.parseFieldMatcher());
        return queryParseContext;
    }
}
