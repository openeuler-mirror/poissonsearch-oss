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

package org.elasticsearch.search.facets.terms;

import org.apache.lucene.index.IndexReader;
import org.elasticsearch.common.collect.BoundedTreeSet;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.ImmutableSet;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.thread.ThreadLocals;
import org.elasticsearch.common.trove.TObjectIntHashMap;
import org.elasticsearch.common.trove.TObjectIntIterator;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldData;
import org.elasticsearch.index.field.function.FieldsFunction;
import org.elasticsearch.index.field.function.script.ScriptFieldsFunction;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.search.facets.Facet;
import org.elasticsearch.search.facets.support.AbstractFacetCollector;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author kimchy (shay.banon)
 */
public class TermsFacetCollector extends AbstractFacetCollector {

    private static ThreadLocal<ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<String>>>> cache = new ThreadLocal<ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<String>>>>() {
        @Override protected ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<String>>> initialValue() {
            return new ThreadLocals.CleanableValue<Deque<TObjectIntHashMap<java.lang.String>>>(new ArrayDeque<TObjectIntHashMap<String>>());
        }
    };


    private final FieldDataCache fieldDataCache;

    private final String fieldName;

    private final String indexFieldName;

    private final InternalTermsFacet.ComparatorType comparatorType;

    private final int size;

    private final int numberOfShards;

    private final FieldData.Type fieldDataType;

    private FieldData fieldData;

    private final StaticAggregatorValueProc aggregator;

    private final FieldsFunction scriptFunction;

    public TermsFacetCollector(String facetName, String fieldName, int size, InternalTermsFacet.ComparatorType comparatorType, SearchContext context,
                               ImmutableSet<String> excluded, Pattern pattern, String scriptLang, String script, Map<String, Object> params) {
        super(facetName);
        this.fieldDataCache = context.fieldDataCache();
        this.size = size;
        this.comparatorType = comparatorType;
        this.numberOfShards = context.numberOfShards();

        this.fieldName = fieldName;

        MapperService.SmartNameFieldMappers smartMappers = context.mapperService().smartName(fieldName);
        if (smartMappers == null || !smartMappers.hasMapper()) {
            this.indexFieldName = fieldName;
            this.fieldDataType = FieldData.Type.STRING;
        } else {
            // add type filter if there is exact doc mapper associated with it
            if (smartMappers.hasDocMapper()) {
                setFilter(context.filterCache().cache(smartMappers.docMapper().typeFilter()));
            }

            this.indexFieldName = smartMappers.mapper().names().indexName();
            this.fieldDataType = smartMappers.mapper().fieldDataType();
        }

        if (script != null) {
            scriptFunction = new ScriptFieldsFunction(scriptLang, script, context.scriptService(), context.mapperService(), fieldDataCache);
            if (params == null) {
                params = Maps.newHashMapWithExpectedSize(1);
            }
        } else {
            params = null;
            scriptFunction = null;
        }

        if (excluded.isEmpty() && pattern == null && scriptFunction == null) {
            aggregator = new StaticAggregatorValueProc(popFacets());
        } else {
            aggregator = new AggregatorValueProc(popFacets(), excluded, pattern, this.scriptFunction, params);
        }
    }

    @Override protected void doSetNextReader(IndexReader reader, int docBase) throws IOException {
        fieldData = fieldDataCache.cache(fieldDataType, reader, indexFieldName);
        if (scriptFunction != null) {
            scriptFunction.setNextReader(reader);
        }
    }

    @Override protected void doCollect(int doc) throws IOException {
        fieldData.forEachValueInDoc(doc, aggregator);
    }

    @Override public Facet facet() {
        TObjectIntHashMap<String> facets = aggregator.facets();
        if (facets.isEmpty()) {
            pushFacets(facets);
            return new InternalTermsFacet(facetName, fieldName, comparatorType, size, ImmutableList.<InternalTermsFacet.Entry>of());
        } else {
            // we need to fetch facets of "size * numberOfShards" because of problems in how they are distributed across shards
            BoundedTreeSet<InternalTermsFacet.Entry> ordered = new BoundedTreeSet<InternalTermsFacet.Entry>(InternalTermsFacet.ComparatorType.COUNT.comparator(), size * numberOfShards);
            for (TObjectIntIterator<String> it = facets.iterator(); it.hasNext();) {
                it.advance();
                ordered.add(new InternalTermsFacet.Entry(it.key(), it.value()));
            }
            pushFacets(facets);
            return new InternalTermsFacet(facetName, fieldName, comparatorType, size, ordered);
        }
    }

    private TObjectIntHashMap<String> popFacets() {
        Deque<TObjectIntHashMap<String>> deque = cache.get().get();
        if (deque.isEmpty()) {
            deque.add(new TObjectIntHashMap<String>());
        }
        TObjectIntHashMap<String> facets = deque.pollFirst();
        facets.clear();
        return facets;
    }

    private void pushFacets(TObjectIntHashMap<String> facets) {
        facets.clear();
        Deque<TObjectIntHashMap<String>> deque = cache.get().get();
        if (deque != null) {
            deque.add(facets);
        }
    }

    public static class AggregatorValueProc extends StaticAggregatorValueProc {

        private final ImmutableSet<String> excluded;

        private final Matcher matcher;

        private final FieldsFunction scriptFunction;

        private final Map<String, Object> params;

        public AggregatorValueProc(TObjectIntHashMap<String> facets, ImmutableSet<String> excluded, Pattern pattern,
                                   FieldsFunction scriptFunction, Map<String, Object> params) {
            super(facets);
            this.excluded = excluded;
            this.matcher = pattern != null ? pattern.matcher("") : null;
            this.scriptFunction = scriptFunction;
            this.params = params;
        }

        @Override public void onValue(int docId, String value) {
            if (excluded != null && excluded.contains(value)) {
                return;
            }
            if (matcher != null && !matcher.reset(value).matches()) {
                return;
            }
            if (scriptFunction != null) {
                params.put("term", value);
                Object scriptValue = scriptFunction.execute(docId, params);
                if (scriptValue == null) {
                    return;
                }
                if (scriptValue instanceof Boolean) {
                    if (!((Boolean) scriptValue)) {
                        return;
                    }
                } else {
                    value = scriptValue.toString();
                }
            }
            super.onValue(docId, value);
        }
    }

    public static class StaticAggregatorValueProc implements FieldData.StringValueInDocProc {

        private final TObjectIntHashMap<String> facets;

        public StaticAggregatorValueProc(TObjectIntHashMap<String> facets) {
            this.facets = facets;
        }

        @Override public void onValue(int docId, String value) {
            facets.adjustOrPutValue(value, 1, 1);
        }

        public final TObjectIntHashMap<String> facets() {
            return facets;
        }
    }
}
