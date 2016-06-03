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

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.PipelineAggregatorBuilder;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsParser;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsPipelineAggregatorBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class MinBucketPipelineAggregatorBuilder extends BucketMetricsPipelineAggregatorBuilder<MinBucketPipelineAggregatorBuilder> {
    public static final String NAME = MinBucketPipelineAggregator.TYPE.name();
    public static final ParseField AGGREGATION_FIELD_NAME = new ParseField(NAME);

    public MinBucketPipelineAggregatorBuilder(String name, String bucketsPath) {
        super(name, MinBucketPipelineAggregator.TYPE.name(), new String[] { bucketsPath });
    }

    /**
     * Read from a stream.
     */
    public MinBucketPipelineAggregatorBuilder(StreamInput in) throws IOException {
        super(in, NAME);
    }

    @Override
    protected void innerWriteTo(StreamOutput out) throws IOException {
        // Do nothing, no extra state to write to stream
    }

    @Override
    protected PipelineAggregator createInternal(Map<String, Object> metaData) throws IOException {
        return new MinBucketPipelineAggregator(name, bucketsPaths, gapPolicy(), formatter(), metaData);
    }

    @Override
    public void doValidate(AggregatorFactory<?> parent, AggregatorFactory<?>[] aggFactories,
            List<PipelineAggregatorBuilder> pipelineAggregatorFactories) {
        if (bucketsPaths.length != 1) {
            throw new IllegalStateException(PipelineAggregator.Parser.BUCKETS_PATH.getPreferredName()
                    + " must contain a single entry for aggregation [" + name + "]");
        }
    }

    @Override
    protected XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        return builder;
    }

    public static final PipelineAggregator.Parser PARSER = new BucketMetricsParser() {
        @Override
        protected MinBucketPipelineAggregatorBuilder buildFactory(String pipelineAggregatorName,
                String bucketsPath, Map<String, Object> params) {
            return new MinBucketPipelineAggregatorBuilder(pipelineAggregatorName, bucketsPath);
        }
    };

    @Override
    protected int innerHashCode() {
        return 0;
    }

    @Override
    protected boolean innerEquals(BucketMetricsPipelineAggregatorBuilder<MinBucketPipelineAggregatorBuilder> other) {
        return true;
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }
}
