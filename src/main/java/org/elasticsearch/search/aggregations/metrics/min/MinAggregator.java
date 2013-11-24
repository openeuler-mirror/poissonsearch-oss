/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.aggregations.metrics.min;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.index.fielddata.DoubleValues;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.support.numeric.NumericValuesSource;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.support.ValueSourceAggregatorFactory;

import java.io.IOException;

/**
 *
 */
public class MinAggregator extends Aggregator {

    private final NumericValuesSource valuesSource;

    private DoubleArray mins;

    public MinAggregator(String name, long estimatedBucketsCount, NumericValuesSource valuesSource, AggregationContext context, Aggregator parent) {
        super(name, BucketAggregationMode.MULTI_BUCKETS, AggregatorFactories.EMPTY, estimatedBucketsCount, context, parent);
        this.valuesSource = valuesSource;
        if (valuesSource != null) {
            if (valuesSource != null) {
                final long initialSize = estimatedBucketsCount < 2 ? 1 : estimatedBucketsCount;
                mins = BigArrays.newDoubleArray(initialSize);
                mins.fill(0, mins.size(), Double.POSITIVE_INFINITY);
            }
        }
    }

    @Override
    public boolean shouldCollect() {
        return valuesSource != null;
    }

    @Override
    public void collect(int doc, long owningBucketOrdinal) throws IOException {
        assert valuesSource != null : "collect must only be called if #shouldCollect returns true";

        DoubleValues values = valuesSource.doubleValues();
        if (values == null || values.setDocument(doc) == 0) {
            return;
        }

        if (owningBucketOrdinal >= mins.size()) {
            long from = mins.size();
            mins = BigArrays.grow(mins, owningBucketOrdinal + 1);
            mins.fill(from, mins.size(), Double.POSITIVE_INFINITY);
        }

        mins.set(owningBucketOrdinal, Math.min(values.nextValue(), mins.get(owningBucketOrdinal)));
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        if (valuesSource == null) {
            return new InternalMin(name, Double.POSITIVE_INFINITY);
        }
        assert owningBucketOrdinal < mins.size();
        return new InternalMin(name, mins.get(owningBucketOrdinal));
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalMin(name, Double.POSITIVE_INFINITY);
    }

    public static class Factory extends ValueSourceAggregatorFactory.LeafOnly<NumericValuesSource> {

        public Factory(String name, ValuesSourceConfig<NumericValuesSource> valuesSourceConfig) {
            super(name, InternalMin.TYPE.name(), valuesSourceConfig);
        }

        @Override
        protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent) {
            return new MinAggregator(name, 0, null, aggregationContext, parent);
        }

        @Override
        protected Aggregator create(NumericValuesSource valuesSource, long expectedBucketsCount, AggregationContext aggregationContext, Aggregator parent) {
            return new MinAggregator(name, expectedBucketsCount, valuesSource, aggregationContext, parent);
        }
    }
}
