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

package org.elasticsearch.index.query;

import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * A filter that matches documents matching boolean combinations of other filters.
 */
public class NotQueryBuilder extends QueryBuilder {

    public static final String NAME = "not";

    private final QueryBuilder filter;

    private String queryName;

    static final NotQueryBuilder PROTOTYPE = new NotQueryBuilder();

    public NotQueryBuilder(QueryBuilder filter) {
        this.filter = Objects.requireNonNull(filter);
    }

    /**
     * private constructor for internal use
     */
    private NotQueryBuilder() {
        this.filter = null;
    }

    public NotQueryBuilder queryName(String queryName) {
        this.queryName = queryName;
        return this;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field("query");
        filter.toXContent(builder, params);
        if (queryName != null) {
            builder.field("_name", queryName);
        }
        builder.endObject();
    }

    @Override
    public String queryId() {
        return NAME;
    }
}