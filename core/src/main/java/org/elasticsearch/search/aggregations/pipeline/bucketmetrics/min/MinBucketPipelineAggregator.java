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

package org.elasticsearch.search.aggregations.pipeline.bucketmetrics.min;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregation.Type;
import org.elasticsearch.search.aggregations.pipeline.BucketHelpers.GapPolicy;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorBuilder;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorStreams;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsPipelineAggregatorBuilder;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsPipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.InternalBucketMetricValue;
import org.elasticsearch.search.aggregations.support.format.ValueFormatter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MinBucketPipelineAggregator extends BucketMetricsPipelineAggregator {

    public final static Type TYPE = new Type("min_bucket");

    public final static PipelineAggregatorStreams.Stream STREAM = new PipelineAggregatorStreams.Stream() {
        @Override
        public MinBucketPipelineAggregator readResult(StreamInput in) throws IOException {
            MinBucketPipelineAggregator result = new MinBucketPipelineAggregator();
            result.readFrom(in);
            return result;
        }
    };

    public static void registerStreams() {
        PipelineAggregatorStreams.registerStream(STREAM, TYPE.stream());
    }

    private List<String> minBucketKeys;
    private double minValue;

    private MinBucketPipelineAggregator() {
    }

    protected MinBucketPipelineAggregator(String name, String[] bucketsPaths, GapPolicy gapPolicy, ValueFormatter formatter,
            Map<String, Object> metaData) {
        super(name, bucketsPaths, gapPolicy, formatter, metaData);
    }

    @Override
    public Type type() {
        return TYPE;
    }

    @Override
    protected void preCollection() {
        minBucketKeys = new ArrayList<>();
        minValue = Double.POSITIVE_INFINITY;
    }

    @Override
    protected void collectBucketValue(String bucketKey, Double bucketValue) {
        if (bucketValue < minValue) {
            minBucketKeys.clear();
            minBucketKeys.add(bucketKey);
            minValue = bucketValue;
        } else if (bucketValue.equals(minValue)) {
            minBucketKeys.add(bucketKey);
        }
    }

    @Override
    protected InternalAggregation buildAggregation(java.util.List<PipelineAggregator> pipelineAggregators,
            java.util.Map<String, Object> metadata) {
        String[] keys = minBucketKeys.toArray(new String[minBucketKeys.size()]);
        return new InternalBucketMetricValue(name(), keys, minValue, formatter, Collections.emptyList(), metaData());
    };

    public static class MinBucketPipelineAggregatorBuilder
            extends BucketMetricsPipelineAggregatorBuilder<MinBucketPipelineAggregatorBuilder> {

        static final MinBucketPipelineAggregatorBuilder PROTOTYPE = new MinBucketPipelineAggregatorBuilder("", "");

        public MinBucketPipelineAggregatorBuilder(String name, String bucketsPath) {
            this(name, new String[] { bucketsPath });
        }

        private MinBucketPipelineAggregatorBuilder(String name, String[] bucketsPaths) {
            super(name, TYPE.name(), bucketsPaths);
        }

        @Override
        protected PipelineAggregator createInternal(Map<String, Object> metaData) throws IOException {
            return new MinBucketPipelineAggregator(name, bucketsPaths, gapPolicy(), formatter(), metaData);
        }

        @Override
        public void doValidate(AggregatorFactory<?> parent, AggregatorFactory<?>[] aggFactories,
                List<PipelineAggregatorBuilder<?>> pipelineAggregatorFactories) {
            if (bucketsPaths.length != 1) {
                throw new IllegalStateException(PipelineAggregator.Parser.BUCKETS_PATH.getPreferredName()
                        + " must contain a single entry for aggregation [" + name + "]");
            }
        }

        @Override
        protected XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
            return builder;
        }

        @Override
        protected MinBucketPipelineAggregatorBuilder innerReadFrom(String name, String[] bucketsPaths, StreamInput in)
                throws IOException {
            return new MinBucketPipelineAggregatorBuilder(name, bucketsPaths);
        }

        @Override
        protected void innerWriteTo(StreamOutput out) throws IOException {
            // Do nothing, no extra state to write to stream
        }

        @Override
        protected int innerHashCode() {
            return 0;
        }

        @Override
        protected boolean innerEquals(BucketMetricsPipelineAggregatorBuilder<MinBucketPipelineAggregatorBuilder> other) {
            return true;
        }

    }

}
