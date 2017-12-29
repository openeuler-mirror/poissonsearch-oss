/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.querydsl.agg;

import org.elasticsearch.search.aggregations.AggregationBuilder;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.bucket.terms.TermsAggregationBuilder;
import org.elasticsearch.xpack.sql.querydsl.container.Sort;
import org.elasticsearch.xpack.sql.querydsl.container.Sort.Direction;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.elasticsearch.search.aggregations.AggregationBuilders.terms;

public class GroupByColumnAgg extends GroupingAgg {

    private static final int DEFAULT_LIMIT = 512;
    private final int limit;

    public GroupByColumnAgg(String id, String propertyPath, String fieldName) {
        this(id, propertyPath, fieldName, emptyList(), emptyList(), emptyMap(), -1);
    }

    public GroupByColumnAgg(String id, String propertyPath, String fieldName, List<LeafAgg> subAggs,
            List<PipelineAgg> subPipelines, Map<String, Direction> order, int limit) {
        super(id, propertyPath, fieldName, subAggs, subPipelines, order);
        this.limit = limit < 0 ? DEFAULT_LIMIT : Math.min(limit, DEFAULT_LIMIT);
    }

    public int limit() {
        return limit;
    }

    @Override
    protected AggregationBuilder toGroupingAgg() {
        // TODO: the size should be configurable
        TermsAggregationBuilder terms = termsTarget(terms(id()).size(limit));

        List<BucketOrder> termOrders = emptyList();
        if (!order().isEmpty()) {
            termOrders = new ArrayList<>();
            for (Entry<String, Sort.Direction> entry : order().entrySet()) {
                String key = entry.getKey();
                boolean asc = entry.getValue() == Direction.ASC;
                BucketOrder o = null;
                // special cases
                if (GROUP_KEY_SORTING.equals(key)) {
                    o = BucketOrder.key(asc);
                }
                else if (GROUP_COUNT_SORTING.equals(key)) {
                    o = BucketOrder.count(asc);
                }
                else {
                    o = BucketOrder.aggregation(key, asc);
                }
                termOrders.add(o);
            }
            terms.order(termOrders);
        }

        terms.minDocCount(1);
        return terms;
    }

    protected TermsAggregationBuilder termsTarget(TermsAggregationBuilder builder) {
        return builder.field(fieldName());
    }

    @Override
    protected GroupByColumnAgg copy(String id, String propertyPath, String fieldName, List<LeafAgg> subAggs,
            List<PipelineAgg> subPipelines, Map<String, Direction> order) {
        return new GroupByColumnAgg(id, propertyPath, fieldName, subAggs, subPipelines, order, limit);
    }

    public GroupByColumnAgg withLimit(int limit) {
        return new GroupByColumnAgg(id(), propertyPath(), fieldName(), subAggs(), subPipelines(), order(), limit);
    }
}
