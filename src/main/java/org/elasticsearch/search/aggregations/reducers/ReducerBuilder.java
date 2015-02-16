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

package org.elasticsearch.search.aggregations.reducers;

import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * A base class for all reducer builders.
 */
public abstract class ReducerBuilder<B extends ReducerBuilder<B>> implements ToXContent {

    private final String name;
    protected final String type;
    private List<String> bucketsPaths;
    private Map<String, Object> metaData;

    /**
     * Sole constructor, typically used by sub-classes.
     */
    protected ReducerBuilder(String name, String type) {
        this.name = name;
        this.type = type;
    }

    /**
     * Return the name of the reducer that is being built.
     */
    public String getName() {
        return name;
    }

    /**
     * Sets the paths to the buckets to use for this reducer
     */
    public B setBucketsPaths(List<String> bucketsPaths) {
        this.bucketsPaths = bucketsPaths;
        return (B) this;
    }

    /**
     * Sets the meta data to be included in the reducer's response
     */
    public B setMetaData(Map<String, Object> metaData) {
        this.metaData = metaData;
        return (B)this;
    }

    @Override
    public final XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(getName());

        if (this.metaData != null) {
            builder.field("meta", this.metaData);
        }
        builder.startObject(type);

        if (bucketsPaths != null) {
            builder.startArray(Reducer.Parser.BUCKETS_PATH.getPreferredName());
            for (String path : bucketsPaths) {
                builder.value(path);
            }
            builder.endArray();
        }

        internalXContent(builder, params);

        builder.endObject();

        return builder.endObject();
    }

    protected abstract XContentBuilder internalXContent(XContentBuilder builder, Params params) throws IOException;
}
