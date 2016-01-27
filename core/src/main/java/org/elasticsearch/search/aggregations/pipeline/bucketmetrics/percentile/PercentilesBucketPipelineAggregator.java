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

package org.elasticsearch.search.aggregations.pipeline.bucketmetrics.percentile;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.InternalAggregation;
import org.elasticsearch.search.aggregations.InternalAggregation.Type;
import org.elasticsearch.search.aggregations.pipeline.BucketHelpers.GapPolicy;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorFactory;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregatorStreams;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsFactory;
import org.elasticsearch.search.aggregations.pipeline.bucketmetrics.BucketMetricsPipelineAggregator;
import org.elasticsearch.search.aggregations.support.format.ValueFormatter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PercentilesBucketPipelineAggregator extends BucketMetricsPipelineAggregator {

    public final static Type TYPE = new Type("percentiles_bucket");
    public final ParseField PERCENTS_FIELD = new ParseField("percents");

    public final static PipelineAggregatorStreams.Stream STREAM = new PipelineAggregatorStreams.Stream() {
        @Override
        public PercentilesBucketPipelineAggregator readResult(StreamInput in) throws IOException {
            PercentilesBucketPipelineAggregator result = new PercentilesBucketPipelineAggregator();
            result.readFrom(in);
            return result;
        }
    };

    public static void registerStreams() {
        PipelineAggregatorStreams.registerStream(STREAM, TYPE.stream());
        InternalPercentilesBucket.registerStreams();
    }

    private double[] percents;
    private List<Double> data;

    private PercentilesBucketPipelineAggregator() {
    }

    protected PercentilesBucketPipelineAggregator(String name, double[] percents, String[] bucketsPaths, GapPolicy gapPolicy,
                                                  ValueFormatter formatter, Map<String, Object> metaData) {
        super(name, bucketsPaths, gapPolicy, formatter, metaData);
        this.percents = percents;
    }

    @Override
    public Type type() {
        return TYPE;
    }

    @Override
    protected void preCollection() {
       data = new ArrayList<>(1024);
    }

    @Override
    protected void collectBucketValue(String bucketKey, Double bucketValue) {
        data.add(bucketValue);
    }

    @Override
    protected InternalAggregation buildAggregation(List<PipelineAggregator> pipelineAggregators, Map<String, Object> metadata) {

        // Perform the sorting and percentile collection now that all the data
        // has been collected.
        Collections.sort(data);

        double[] percentiles = new double[percents.length];
        if (data.size() == 0) {
            for (int i = 0; i < percents.length; i++) {
                percentiles[i] = Double.NaN;
            }
        } else {
            for (int i = 0; i < percents.length; i++) {
                int index = (int)((percents[i] / 100.0) * data.size());
                percentiles[i] = data.get(index);
            }
        }

        // todo need postCollection() to clean up temp sorted data?

        return new InternalPercentilesBucket(name(), percents, percentiles, formatter, pipelineAggregators, metadata);
    }

    @Override
    public void doReadFrom(StreamInput in) throws IOException {
        super.doReadFrom(in);
        percents = in.readDoubleArray();
    }

    @Override
    public void doWriteTo(StreamOutput out) throws IOException {
        super.doWriteTo(out);
        out.writeDoubleArray(percents);
    }

    public static class Factory extends BucketMetricsFactory<Factory> {

        private double[] percents = new double[] { 1.0, 5.0, 25.0, 50.0, 75.0, 95.0, 99.0 };

        public Factory(String name, String bucketsPath) {
            this(name, new String[] { bucketsPath });
        }

        private Factory(String name, String[] bucketsPaths) {
            super(name, TYPE.name(), bucketsPaths);
        }

        /**
         * Get the percentages to calculate percentiles for in this aggregation
         */
        public double[] percents() {
            return percents;
        }

        /**
         * Set the percentages to calculate percentiles for in this aggregation
         */
        public Factory percents(double[] percents) {
            for (Double p : percents) {
                if (p == null || p < 0.0 || p > 100.0) {
                    throw new IllegalArgumentException(PercentilesBucketParser.PERCENTS.getPreferredName()
                            + " must only contain non-null doubles from 0.0-100.0 inclusive");
                }
            }
            this.percents = percents;
            return this;
        }

        @Override
        protected PipelineAggregator createInternal(Map<String, Object> metaData) throws IOException {
            return new PercentilesBucketPipelineAggregator(name, percents, bucketsPaths, gapPolicy(), formatter(), metaData);
        }

        @Override
        public void doValidate(AggregatorFactory<?> parent, AggregatorFactory<?>[] aggFactories,
                List<PipelineAggregatorFactory> pipelineAggregatorFactories) {
            if (bucketsPaths.length != 1) {
                throw new IllegalStateException(PipelineAggregator.Parser.BUCKETS_PATH.getPreferredName()
                        + " must contain a single entry for aggregation [" + name + "]");
            }

            for (Double p : percents) {
                if (p == null || p < 0.0 || p > 100.0) {
                    throw new IllegalStateException(PercentilesBucketParser.PERCENTS.getPreferredName()
                            + " must only contain non-null doubles from 0.0-100.0 inclusive");
                }
            }
        }

        @Override
        protected XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
            if (percents != null) {
                builder.field(PercentilesBucketParser.PERCENTS.getPreferredName(), percents);
            }
            return builder;
        }

        @Override
        protected BucketMetricsFactory innerReadFrom(String name, String[] bucketsPaths, StreamInput in) throws IOException {
            Factory factory = new Factory(name, bucketsPaths);
            factory.percents = in.readDoubleArray();
            return factory;
        }

        @Override
        protected void innerWriteTo(StreamOutput out) throws IOException {
            out.writeDoubleArray(percents);
        }

        @Override
        protected int innerHashCode() {
            return Arrays.hashCode(percents);
        }

        @Override
        protected boolean innerEquals(BucketMetricsFactory obj) {
            Factory other = (Factory) obj;
            return Objects.deepEquals(percents, other.percents);
        }

    }

}
