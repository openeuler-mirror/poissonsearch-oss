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

package org.elasticsearch.search.aggregations.metrics.stats;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.DoubleArray;
import org.elasticsearch.common.util.LongArray;
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
public class StatsAggegator extends Aggregator {

    private final NumericValuesSource valuesSource;

    private LongArray counts;
    private DoubleArray sums;
    private DoubleArray mins;
    private DoubleArray maxes;

    public StatsAggegator(String name, long estimatedBucketsCount, NumericValuesSource valuesSource, AggregationContext context, Aggregator parent) {
        super(name, BucketAggregationMode.MULTI_BUCKETS, AggregatorFactories.EMPTY, estimatedBucketsCount, context, parent);
        this.valuesSource = valuesSource;
        if (valuesSource != null) {
            final long initialSize = estimatedBucketsCount < 2 ? 1 : estimatedBucketsCount;
            counts = BigArrays.newLongArray(initialSize);
            sums = BigArrays.newDoubleArray(initialSize);
            mins = BigArrays.newDoubleArray(initialSize);
            mins.fill(0, mins.size(), Double.POSITIVE_INFINITY);
            maxes = BigArrays.newDoubleArray(initialSize);
            maxes.fill(0, maxes.size(), Double.NEGATIVE_INFINITY);
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
        if (values == null) {
            return;
        }

        if (owningBucketOrdinal >= counts.size()) {
            final long from = counts.size();
            final long overSize = BigArrays.overSize(owningBucketOrdinal + 1);
            counts = BigArrays.resize(counts, overSize);
            sums = BigArrays.resize(sums, overSize);
            mins = BigArrays.resize(mins, overSize);
            maxes = BigArrays.resize(maxes, overSize);
            mins.fill(from, overSize, Double.POSITIVE_INFINITY);
            maxes.fill(from, overSize, Double.NEGATIVE_INFINITY);
        }

        final int valuesCount = values.setDocument(doc);
        counts.increment(owningBucketOrdinal, valuesCount);
        double sum = 0;
        double min = mins.get(owningBucketOrdinal);
        double max = maxes.get(owningBucketOrdinal);
        for (int i = 0; i < valuesCount; i++) {
            double value = values.nextValue();
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        sums.increment(owningBucketOrdinal, sum);
        mins.set(owningBucketOrdinal, min);
        maxes.set(owningBucketOrdinal, max);
    }

    @Override
    public InternalAggregation buildAggregation(long owningBucketOrdinal) {
        if (valuesSource == null) {
            return new InternalStats(name, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
        }
        assert owningBucketOrdinal < counts.size();
        return new InternalStats(name, counts.get(owningBucketOrdinal), sums.get(owningBucketOrdinal), mins.get(owningBucketOrdinal), maxes.get(owningBucketOrdinal));
    }

    @Override
    public InternalAggregation buildEmptyAggregation() {
        return new InternalStats(name, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    public static class Factory extends ValueSourceAggregatorFactory.LeafOnly<NumericValuesSource> {

        public Factory(String name, ValuesSourceConfig<NumericValuesSource> valuesSourceConfig) {
            super(name, InternalStats.TYPE.name(), valuesSourceConfig);
        }

        @Override
        protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent) {
            return new StatsAggegator(name, 0, null, aggregationContext, parent);
        }

        @Override
        protected Aggregator create(NumericValuesSource valuesSource, long expectedBucketsCount, AggregationContext aggregationContext, Aggregator parent) {
            return new StatsAggegator(name, expectedBucketsCount, valuesSource, aggregationContext, parent);
        }
    }
}
