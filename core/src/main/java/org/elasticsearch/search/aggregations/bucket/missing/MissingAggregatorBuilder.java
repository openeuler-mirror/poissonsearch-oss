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

package org.elasticsearch.search.aggregations.bucket.missing;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.AggregatorFactories.Builder;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.ValueType;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorBuilder;
import org.elasticsearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;
import org.elasticsearch.search.aggregations.support.ValuesSourceType;

import java.io.IOException;

public class MissingAggregatorBuilder extends ValuesSourceAggregatorBuilder<ValuesSource, MissingAggregatorBuilder> {

    static final MissingAggregatorBuilder PROTOTYPE = new MissingAggregatorBuilder("", null);

    public MissingAggregatorBuilder(String name, ValueType targetValueType) {
        super(name, InternalMissing.TYPE, ValuesSourceType.ANY, targetValueType);
    }

    @Override
    protected ValuesSourceAggregatorFactory<ValuesSource, ?> innerBuild(AggregationContext context,
            ValuesSourceConfig<ValuesSource> config, AggregatorFactory<?> parent, Builder subFactoriesBuilder) throws IOException {
        return new MissingAggregatorFactory(name, type, config, context, parent, subFactoriesBuilder, metaData);
    }

    @Override
    protected MissingAggregatorBuilder innerReadFrom(String name, ValuesSourceType valuesSourceType,
            ValueType targetValueType, StreamInput in) {
        return new MissingAggregatorBuilder(name, targetValueType);
    }

    @Override
    protected void innerWriteTo(StreamOutput out) {
        // Do nothing, no extra state to write to stream
    }

    @Override
    public XContentBuilder doXContentBody(XContentBuilder builder, Params params) throws IOException {
        return builder;
    }

    @Override
    protected int innerHashCode() {
        return 0;
    }

    @Override
    protected boolean innerEquals(Object obj) {
        return true;
    }
}