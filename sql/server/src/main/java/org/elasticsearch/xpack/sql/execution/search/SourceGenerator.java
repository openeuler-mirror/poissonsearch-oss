/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.execution.search;

import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.script.Script;
import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.PipelineAggregationBuilder;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.fetch.StoredFieldsContext;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.NestedSortBuilder;
import org.elasticsearch.search.sort.ScriptSortBuilder.ScriptSortType;
import org.elasticsearch.search.sort.SortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.xpack.sql.SqlIllegalArgumentException;
import org.elasticsearch.xpack.sql.expression.Attribute;
import org.elasticsearch.xpack.sql.expression.FieldAttribute;
import org.elasticsearch.xpack.sql.expression.NestedFieldAttribute;
import org.elasticsearch.xpack.sql.expression.RootFieldAttribute;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.definition.ProcessorDefinition;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.definition.ReferenceInput;
import org.elasticsearch.xpack.sql.querydsl.agg.Aggs;
import org.elasticsearch.xpack.sql.querydsl.container.AttributeSort;
import org.elasticsearch.xpack.sql.querydsl.container.ColumnReference;
import org.elasticsearch.xpack.sql.querydsl.container.ComputedRef;
import org.elasticsearch.xpack.sql.querydsl.container.QueryContainer;
import org.elasticsearch.xpack.sql.querydsl.container.ScriptFieldRef;
import org.elasticsearch.xpack.sql.querydsl.container.ScriptSort;
import org.elasticsearch.xpack.sql.querydsl.container.SearchHitFieldRef;
import org.elasticsearch.xpack.sql.querydsl.container.Sort;
import org.elasticsearch.xpack.sql.querydsl.container.Sort.Direction;
import org.elasticsearch.xpack.sql.querydsl.query.NestedQuery;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import static java.util.Collections.singletonList;
import static org.elasticsearch.search.sort.SortBuilders.fieldSort;
import static org.elasticsearch.search.sort.SortBuilders.scriptSort;

public abstract class SourceGenerator {

    private static final List<String> NO_STORED_FIELD = singletonList(StoredFieldsContext._NONE_);

    public static SearchSourceBuilder sourceBuilder(QueryContainer container, QueryBuilder filter, Integer size) {
        SearchSourceBuilder source = new SearchSourceBuilder();
        // add the source
        if (container.query() != null) {
            if (filter != null) {
                source.query(new BoolQueryBuilder().must(container.query().asBuilder()).filter(filter));
            } else {
                source.query(container.query().asBuilder());
            }
        } else {
            if (filter != null) {
                source.query(new ConstantScoreQueryBuilder(filter));
            }
        }

        // translate fields to source-fields or script fields
        Set<String> sourceFields = new LinkedHashSet<>();
        Set<String> docFields = new LinkedHashSet<>();
        Map<String, Script> scriptFields = new LinkedHashMap<>();

        for (ColumnReference ref : container.columns()) {
            collectFields(ref, sourceFields, docFields, scriptFields);
        }

        if (!sourceFields.isEmpty()) {
            source.fetchSource(sourceFields.toArray(new String[sourceFields.size()]), null);
        }

        for (String field : docFields) {
            source.docValueField(field);
        }

        for (Entry<String, Script> entry : scriptFields.entrySet()) {
            source.scriptField(entry.getKey(), entry.getValue());
        }

        sorting(container, source);

        // add the aggs
        Aggs aggs = container.aggs();
        for (AggregationBuilder builder : aggs.asAggBuilders()) {
            source.aggregation(builder);
        }

        // add the pipeline aggs
        for (PipelineAggregationBuilder builder : aggs.asPipelineBuilders()) {
            source.aggregation(builder);
        }

        optimize(container, source);

        // set size
        if (size != null) {
            if (source.size() == -1) {
                int sz = container.limit() > 0 ? Math.min(container.limit(), size) : size;
                source.size(sz);
            }
        }

        return source;
    }

    private static void collectFields(ColumnReference ref, Set<String> sourceFields, Set<String> docFields, Map<String, Script> scriptFields) {
        if (ref instanceof ComputedRef) {
            ProcessorDefinition proc = ((ComputedRef) ref).processor();
            proc.forEachUp(l -> collectFields(l.context(), sourceFields, docFields, scriptFields), ReferenceInput.class);
        }
        else if (ref instanceof SearchHitFieldRef) {
            SearchHitFieldRef sh = (SearchHitFieldRef) ref;
            Set<String> collection = sh.useDocValue() ? docFields : sourceFields;
            collection.add(sh.name());
        }
        else if (ref instanceof ScriptFieldRef) {
            ScriptFieldRef sfr = (ScriptFieldRef) ref;
            scriptFields.put(sfr.name(), sfr.script().toPainless());
        }
    }

    private static void sorting(QueryContainer container, SearchSourceBuilder source) {
        if (container.sort() != null) {

            for (Sort sortable : container.sort()) {
                SortBuilder<?> sortBuilder = null;

                if (sortable instanceof AttributeSort) {
                    AttributeSort as = (AttributeSort) sortable;
                    Attribute attr = as.attribute();

                    // sorting only works on not-analyzed fields - look for a multi-field replacement
                    if (attr instanceof FieldAttribute) {
                        FieldAttribute fa = (FieldAttribute) attr;
                        attr = fa.isAnalyzed() ? fa.notAnalyzedAttribute() : attr;
                    }

                    // top-level doc value
                    if (attr instanceof RootFieldAttribute) {
                        sortBuilder = fieldSort(((RootFieldAttribute) attr).name());
                    }
                    if (attr instanceof NestedFieldAttribute) {
                        NestedFieldAttribute nfa = (NestedFieldAttribute) attr;
                        FieldSortBuilder fieldSort = fieldSort(nfa.name());

                        String nestedPath = nfa.parentPath();
                        NestedSortBuilder newSort = new NestedSortBuilder(nestedPath);
                        NestedSortBuilder nestedSort = fieldSort.getNestedSort();

                        if (nestedSort == null) {
                            fieldSort.setNestedSort(newSort);
                        } else {
                            for (; nestedSort.getNestedSort() != null; nestedSort = nestedSort.getNestedSort()) {
                            }
                            nestedSort.setNestedSort(newSort);
                        }

                        nestedSort = newSort;

                        List<QueryBuilder> nestedQuery = new ArrayList<>(1);

                        // copy also the nested queries fr(if any)
                        if (container.query() != null) {
                            container.query().forEachDown(nq -> {
                                // found a match
                                if (nestedPath.equals(nq.path())) {
                                    // get the child query - the nested wrapping and inner hits are not needed
                                    nestedQuery.add(nq.child().asBuilder());
                                }
                            }, NestedQuery.class);
                        }

                        if (nestedQuery.size() > 0) {
                            if (nestedQuery.size() > 1) {
                                throw new SqlIllegalArgumentException("nested query should have been grouped in one place");
                            }
                            nestedSort.setFilter(nestedQuery.get(0));
                        }

                        sortBuilder = fieldSort;
                    }
                }
                if (sortable instanceof ScriptSort) {
                    ScriptSort ss = (ScriptSort) sortable;
                    sortBuilder = scriptSort(ss.script().toPainless(), ss.script().outputType().isNumeric() ? ScriptSortType.NUMBER : ScriptSortType.STRING);
                }

                if (sortBuilder != null) {
                    sortBuilder.order(sortable.direction() == Direction.ASC ? SortOrder.ASC : SortOrder.DESC);
                    source.sort(sortBuilder);
                }
            }
        }
        else {
            // if no sorting is specified, use the _doc one
            source.sort("_doc");
        }
    }

    private static void optimize(QueryContainer query, SearchSourceBuilder source) {
        // if only aggs are needed, don't retrieve any docs
        if (query.isAggsOnly()) {
            source.size(0);
            // disable source fetching (only doc values are used)
            source.fetchSource(FetchSourceContext.DO_NOT_FETCH_SOURCE);
            source.storedFields(NO_STORED_FIELD);
        }
    }
}