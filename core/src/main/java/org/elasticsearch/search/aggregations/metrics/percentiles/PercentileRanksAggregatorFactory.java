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

package org.elasticsearch.search.aggregations.metrics.percentiles;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.metrics.percentiles.hdr.HDRPercentileRanksAggregator;
import org.elasticsearch.search.aggregations.metrics.percentiles.tdigest.InternalTDigestPercentileRanks;
import org.elasticsearch.search.aggregations.metrics.percentiles.tdigest.TDigestPercentileRanksAggregator;
import org.elasticsearch.search.aggregations.pipeline.PipelineAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValueType;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceType;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory.LeafOnly;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class PercentileRanksAggregatorFactory extends LeafOnly<ValuesSource.Numeric, PercentileRanksAggregatorFactory> {

    private double[] values;
    private PercentilesMethod method = PercentilesMethod.TDIGEST;
    private int numberOfSignificantValueDigits = 3;
    private double compression = 100.0;
    private boolean keyed = false;

    public PercentileRanksAggregatorFactory(String name) {
        super(name, InternalTDigestPercentileRanks.TYPE, ValuesSourceType.NUMERIC, ValueType.NUMERIC);
    }

    /**
     * Set the values to compute percentiles from.
     */
    public PercentileRanksAggregatorFactory values(double... values) {
        double[] sortedValues = Arrays.copyOf(values, values.length);
        Arrays.sort(sortedValues);
        this.values = sortedValues;
        return this;
    }

    /**
     * Get the values to compute percentiles from.
     */
    public double[] values() {
        return values;
    }

    /**
     * Set whether the XContent response should be keyed
     */
    public PercentileRanksAggregatorFactory keyed(boolean keyed) {
        this.keyed = keyed;
        return this;
    }

    /**
     * Get whether the XContent response should be keyed
     */
    public boolean keyed() {
        return keyed;
    }

    /**
     * Expert: set the number of significant digits in the values. Only relevant
     * when using {@link PercentilesMethod#HDR}.
     */
    public PercentileRanksAggregatorFactory numberOfSignificantValueDigits(int numberOfSignificantValueDigits) {
        this.numberOfSignificantValueDigits = numberOfSignificantValueDigits;
        return this;
    }

    /**
     * Expert: get the number of significant digits in the values. Only relevant
     * when using {@link PercentilesMethod#HDR}.
     */
    public int numberOfSignificantValueDigits() {
        return numberOfSignificantValueDigits;
    }

    /**
     * Expert: set the compression. Higher values improve accuracy but also
     * memory usage. Only relevant when using {@link PercentilesMethod#TDIGEST}.
     */
    public PercentileRanksAggregatorFactory compression(double compression) {
        this.compression = compression;
        return this;
    }

    /**
     * Expert: get the compression. Higher values improve accuracy but also
     * memory usage. Only relevant when using {@link PercentilesMethod#TDIGEST}.
     */
    public double compression() {
        return compression;
    }

    public PercentileRanksAggregatorFactory method(PercentilesMethod method) {
        this.method = method;
        return this;
    }

    public PercentilesMethod method() {
        return method;
    }

    @Override
    protected Aggregator createUnmapped(AggregationContext aggregationContext, Aggregator parent,
            List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData) throws IOException {
        switch (method) {
        case TDIGEST:
            return new TDigestPercentileRanksAggregator(name, null, aggregationContext, parent, values, compression, keyed,
                    config.formatter(),
                    pipelineAggregators, metaData);
        case HDR:
            return new HDRPercentileRanksAggregator(name, null, aggregationContext, parent, values, numberOfSignificantValueDigits, keyed,
                    config.formatter(), pipelineAggregators, metaData);
        default:
            throw new IllegalStateException("Illegal method [" + method.getName() + "]");
        }
    }

    @Override
    protected Aggregator doCreateInternal(ValuesSource.Numeric valuesSource, AggregationContext aggregationContext, Aggregator parent,
            boolean collectsFromSingleBucket, List<PipelineAggregator> pipelineAggregators, Map<String, Object> metaData)
            throws IOException {
        switch (method) {
        case TDIGEST:
            return new TDigestPercentileRanksAggregator(name, valuesSource, aggregationContext, parent, values, compression, keyed,
                    config.formatter(), pipelineAggregators, metaData);
        case HDR:
        return new HDRPercentileRanksAggregator(name, valuesSource, aggregationContext, parent, values, numberOfSignificantValueDigits,
                keyed, config.formatter(), pipelineAggregators, metaData);
        default:
            throw new IllegalStateException("Illegal method [" + method.getName() + "]");
        }
    }

    @Override
    protected PercentileRanksAggregatorFactory innerReadFrom(String name, ValuesSourceType valuesSourceType,
            ValueType targetValueType, StreamInput in) throws IOException {
        PercentileRanksAggregatorFactory factory = new PercentileRanksAggregatorFactory(name);
        factory.values = in.readDoubleArray();
        factory.keyed = in.readBoolean();
        factory.numberOfSignificantValueDigits = in.readVInt();
        factory.compression = in.readDouble();
        factory.method = PercentilesMethod.TDIGEST.readFrom(in);
        return factory;
    }

    @Override
    protected void innerWriteTo(StreamOutput out) throws IOException {
        out.writeDoubleArray(values);
        out.writeBoolean(keyed);
        out.writeVInt(numberOfSignificantValueDigits);
        out.writeDouble(compression);
        method.writeTo(out);
    }

    @Override
    protected XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        builder.field(PercentileRanksParser.VALUES_FIELD.getPreferredName(), values);
        builder.field(AbstractPercentilesParser.KEYED_FIELD.getPreferredName(), keyed);
        builder.startObject(method.getName());
        if (method == PercentilesMethod.TDIGEST) {
            builder.field(AbstractPercentilesParser.COMPRESSION_FIELD.getPreferredName(), compression);
        } else {
            builder.field(AbstractPercentilesParser.NUMBER_SIGNIFICANT_DIGITS_FIELD.getPreferredName(), numberOfSignificantValueDigits);
        }
        builder.endObject();
        return builder;
    }

    @Override
    protected boolean innerEquals(Object obj) {
        PercentileRanksAggregatorFactory other = (PercentileRanksAggregatorFactory) obj;
        if (!Objects.equals(method, other.method)) {
            return false;
        }
        boolean equalSettings = false;
        switch (method) {
        case HDR:
            equalSettings = Objects.equals(numberOfSignificantValueDigits, other.numberOfSignificantValueDigits);
            break;
        case TDIGEST:
            equalSettings = Objects.equals(compression, other.compression);
            break;
        default:
            throw new IllegalStateException("Illegal method [" + method.getName() + "]");
        }
        return equalSettings
                && Objects.deepEquals(values, other.values)
                && Objects.equals(keyed, other.keyed)
                && Objects.equals(method, other.method);
    }

    @Override
    protected int innerHashCode() {
        switch (method) {
        case HDR:
            return Objects.hash(Arrays.hashCode(values), keyed, numberOfSignificantValueDigits, method);
        case TDIGEST:
            return Objects.hash(Arrays.hashCode(values), keyed, compression, method);
        default:
            throw new IllegalStateException("Illegal method [" + method.getName() + "]");
        }
    }
}
