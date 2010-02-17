/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.index.query.json;

import org.elasticsearch.util.json.JsonBuilder;

import java.io.IOException;

/**
 * @author kimchy (Shay Banon)
 */
public class SpanTermJsonQueryBuilder extends BaseJsonQueryBuilder implements JsonSpanQueryBuilder {

    private final String name;

    private final Object value;

    private float boost = -1;

    public SpanTermJsonQueryBuilder(String name, String value) {
        this(name, (Object) value);
    }

    public SpanTermJsonQueryBuilder(String name, int value) {
        this(name, (Object) value);
    }

    public SpanTermJsonQueryBuilder(String name, long value) {
        this(name, (Object) value);
    }

    public SpanTermJsonQueryBuilder(String name, float value) {
        this(name, (Object) value);
    }

    public SpanTermJsonQueryBuilder(String name, double value) {
        this(name, (Object) value);
    }

    private SpanTermJsonQueryBuilder(String name, Object value) {
        this.name = name;
        this.value = value;
    }

    public SpanTermJsonQueryBuilder boost(float boost) {
        this.boost = boost;
        return this;
    }

    @Override public void doJson(JsonBuilder builder, Params params) throws IOException {
        builder.startObject(SpanTermJsonQueryParser.NAME);
        if (boost == -1) {
            builder.field(name, value);
        } else {
            builder.startObject(name);
            builder.field("value", value);
            builder.field("boost", boost);
            builder.endObject();
        }
        builder.endObject();
    }
}