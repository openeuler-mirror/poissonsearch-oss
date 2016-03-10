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

package org.elasticsearch.search.aggregations.bucket.nested;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.AggregatorBuilder;
import org.elasticsearch.search.aggregations.AggregatorFactory;
import org.elasticsearch.search.aggregations.AggregatorFactories.Builder;
import org.elasticsearch.search.aggregations.support.AggregationContext;

import java.io.IOException;
import java.util.Objects;

public class NestedAggregatorBuilder extends AggregatorBuilder<NestedAggregatorBuilder> {

    static final NestedAggregatorBuilder PROTOTYPE = new NestedAggregatorBuilder("", "");

    private final String path;

    /**
     * @param name
     *            the name of this aggregation
     * @param path
     *            the path to use for this nested aggregation. The path must
     *            match the path to a nested object in the mappings.
     */
    public NestedAggregatorBuilder(String name, String path) {
        super(name, InternalNested.TYPE);
        if (path == null) {
            throw new IllegalArgumentException("[path] must not be null: [" + name + "]");
        }
        this.path = path;
    }

    /**
     * Get the path to use for this nested aggregation.
     */
    public String path() {
        return path;
    }

    @Override
    protected AggregatorFactory<?> doBuild(AggregationContext context, AggregatorFactory<?> parent, Builder subFactoriesBuilder)
            throws IOException {
        return new NestedAggregatorFactory(name, type, path, context, parent, subFactoriesBuilder, metaData);
    }

    @Override
    protected XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(NestedAggregator.PATH_FIELD.getPreferredName(), path);
        builder.endObject();
        return builder;
    }

    @Override
    protected NestedAggregatorBuilder doReadFrom(String name, StreamInput in) throws IOException {
        String path = in.readString();
        NestedAggregatorBuilder factory = new NestedAggregatorBuilder(name, path);
        return factory;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(path);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(path);
    }

    @Override
    protected boolean doEquals(Object obj) {
        NestedAggregatorBuilder other = (NestedAggregatorBuilder) obj;
        return Objects.equals(path, other.path);
    }
}