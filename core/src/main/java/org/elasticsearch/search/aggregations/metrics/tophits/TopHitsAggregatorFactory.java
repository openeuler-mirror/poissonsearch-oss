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

package org.elasticsearch.search.aggregations.metrics.tophits;

import org.apache.lucene.search.Sort;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.InternalAggregation.Type;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.builder.SearchSourceBuilder.ScriptField;
import org.elasticsearch.search.fetch.fielddata.FieldDataFieldsContext;
import org.elasticsearch.search.fetch.fielddata.FieldDataFieldsContext.FieldDataField;
import org.elasticsearch.search.fetch.fielddata.FieldDataFieldsFetchSubPhase;
import org.elasticsearch.search.fetch.source.FetchSourceContext;
import org.elasticsearch.search.highlight.HighlightBuilder;
import org.elasticsearch.search.internal.SubSearchContext;
import org.elasticsearch.search.sort.SortBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class TopHitsAggregatorFactory extends AggregatorFactory<TopHitsAggregatorFactory> {

    private final int from;
    private final int size;
    private final boolean explain;
    private final boolean version;
    private final boolean trackScores;
    private final List<SortBuilder<?>> sorts;
    private final HighlightBuilder highlightBuilder;
    private final List<String> fieldNames;
    private final List<String> fieldDataFields;
    private final Set<ScriptField> scriptFields;
    private final FetchSourceContext fetchSourceContext;

    public TopHitsAggregatorFactory(String name, Type type, int from, int size, boolean explain, boolean version, boolean trackScores,
            List<SortBuilder<?>> sorts, HighlightBuilder highlightBuilder, List<String> fieldNames, List<String> fieldDataFields,
            Set<ScriptField> scriptFields, FetchSourceContext fetchSourceContext, AggregationContext context, AggregatorFactory<?> parent,
            AggregatorFactories.Builder subFactories, Map<String, Object> metaData) throws IOException {
        super(name, type, context, parent, subFactories, metaData);
        this.from = from;
        this.size = size;
        this.explain = explain;
        this.version = version;
        this.trackScores = trackScores;
        this.sorts = sorts;
        this.highlightBuilder = highlightBuilder;
        this.fieldNames = fieldNames;
        this.fieldDataFields = fieldDataFields;
        this.scriptFields = scriptFields;
        this.fetchSourceContext = fetchSourceContext;
    }

    @Override
    public Aggregator createInternal(Aggregator parent, boolean collectsFromSingleBucket, List<PipelineAggregator> pipelineAggregators,
            Map<String, Object> metaData) throws IOException {
        SubSearchContext subSearchContext = new SubSearchContext(context.searchContext());
        subSearchContext.parsedQuery(context.searchContext().parsedQuery());
        subSearchContext.explain(explain);
        subSearchContext.version(version);
        subSearchContext.trackScores(trackScores);
        subSearchContext.from(from);
        subSearchContext.size(size);
        if (sorts != null) {
            Optional<Sort> optionalSort = SortBuilder.buildSort(sorts, subSearchContext.getQueryShardContext());
            if (optionalSort.isPresent()) {
                subSearchContext.sort(optionalSort.get());
            }
        }
        if (fieldNames != null) {
            subSearchContext.fieldNames().addAll(fieldNames);
        }
        if (fieldDataFields != null) {
            FieldDataFieldsContext fieldDataFieldsContext = subSearchContext
                    .getFetchSubPhaseContext(FieldDataFieldsFetchSubPhase.CONTEXT_FACTORY);
            for (String field : fieldDataFields) {
                fieldDataFieldsContext.add(new FieldDataField(field));
            }
            fieldDataFieldsContext.setHitExecutionNeeded(true);
        }
        if (scriptFields != null) {
            for (ScriptField field : scriptFields) {
                SearchScript searchScript = subSearchContext.scriptService().search(subSearchContext.lookup(), field.script(),
                        ScriptContext.Standard.SEARCH, Collections.emptyMap(), subSearchContext.getQueryShardContext().getClusterState());
                subSearchContext.scriptFields().add(new org.elasticsearch.search.fetch.script.ScriptFieldsContext.ScriptField(
                        field.fieldName(), searchScript, field.ignoreFailure()));
            }
        }
        if (fetchSourceContext != null) {
            subSearchContext.fetchSourceContext(fetchSourceContext);
        }
        if (highlightBuilder != null) {
            subSearchContext.highlight(highlightBuilder.build(context.searchContext().getQueryShardContext()));
        }
        return new TopHitsAggregator(context.searchContext().fetchPhase(), subSearchContext, name, context, parent,
                pipelineAggregators, metaData);
    }

}
