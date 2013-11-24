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

package org.elasticsearch.search.aggregations.bucket;

import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.LongArray;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregations;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.AggregatorFactories;

import java.io.IOException;
import java.util.Arrays;

/**
 *
 */
public abstract class BucketsAggregator extends Aggregator {

    protected LongArray docCounts;

    public BucketsAggregator(String name, BucketAggregationMode bucketAggregationMode, AggregatorFactories factories,
                             long estimatedBucketsCount, AggregationContext context, Aggregator parent) {
        super(name, bucketAggregationMode, factories, estimatedBucketsCount, context, parent);
        docCounts = BigArrays.newLongArray(estimatedBucketsCount);
    }

    /**
     * Utility method to collect the given doc in the given bucket (identified by the bucket ordinal)
     */
    protected final void collectBucket(int doc, long bucketOrd) throws IOException {
        docCounts = BigArrays.grow(docCounts, bucketOrd + 1);
        docCounts.increment(bucketOrd, 1);
        for (int i = 0; i < subAggregators.length; i++) {
            subAggregators[i].collect(doc, bucketOrd);
        }
    }

    /**
     * Utility method to collect the given doc in the given bucket but not to update the doc counts of the bucket
     */
    protected final void collectBucketNoCounts(int doc, long bucketOrd) throws IOException {
        for (int i = 0; i < subAggregators.length; i++) {
            subAggregators[i].collect(doc, bucketOrd);
        }
    }

    /**
     * Utility method to increment the doc counts of the given bucket (identified by the bucket ordinal)
     */
    protected final void incrementBucketDocCount(int inc, long bucketOrd) throws IOException {
        docCounts = BigArrays.grow(docCounts, bucketOrd + 1);
        docCounts.increment(bucketOrd, inc);
    }

    /**
     * Utility method to return the number of documents that fell in the given bucket (identified by the bucket ordinal)
     */
    protected final long bucketDocCount(long bucketOrd) {
        assert bucketOrd < docCounts.size();
        return docCounts.get(bucketOrd);
    }

    /**
     * Utility method to build the aggregations of the given bucket (identified by the bucket ordinal)
     */
    protected final InternalAggregations bucketAggregations(long bucketOrd) {
        InternalAggregation[] aggregations = new InternalAggregation[subAggregators.length];
        for (int i = 0; i < subAggregators.length; i++) {
            aggregations[i] = subAggregators[i].buildAggregation(bucketOrd);
        }
        return new InternalAggregations(Arrays.asList(aggregations));
    }

}
