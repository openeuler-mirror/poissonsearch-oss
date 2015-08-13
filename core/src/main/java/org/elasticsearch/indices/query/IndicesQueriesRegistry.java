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

package org.elasticsearch.indices.query;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;

import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.EmptyQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryParser;

import java.util.Map;
import java.util.Set;

public class IndicesQueriesRegistry extends AbstractComponent {

    private ImmutableMap<String, QueryParser<? extends QueryBuilder<? extends QueryBuilder>>> queryParsers;

    @Inject
    public IndicesQueriesRegistry(Settings settings, Set<QueryParser<? extends QueryBuilder<? extends QueryBuilder>>> injectedQueryParsers, NamedWriteableRegistry namedWriteableRegistry) {
        super(settings);
        Map<String, QueryParser<? extends QueryBuilder<? extends QueryBuilder>>> queryParsers = Maps.newHashMap();
        for (QueryParser<? extends QueryBuilder<? extends QueryBuilder>> queryParser : injectedQueryParsers) {
            for (String name : queryParser.names()) {
                queryParsers.put(name, queryParser);
            }
            namedWriteableRegistry.registerPrototype(QueryBuilder.class, queryParser.getBuilderPrototype());
        }
        // EmptyQueryBuilder is not registered as query parser but used internally.
        // We need to register it with the NamedWriteableRegistry in order to serialize it
        namedWriteableRegistry.registerPrototype(QueryBuilder.class, EmptyQueryBuilder.PROTOTYPE);
        this.queryParsers = ImmutableMap.copyOf(queryParsers);
    }

    /**
     * Returns all the registered query parsers
     */
    public ImmutableMap<String, QueryParser<? extends QueryBuilder<? extends QueryBuilder>>> queryParsers() {
        return queryParsers;
    }
}