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

import org.apache.lucene.search.Query;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * A filter that matches documents matching boolean combinations of other filters.
 */
public class NotQueryBuilder extends AbstractQueryBuilder<NotQueryBuilder> {

    public static final String NAME = "not";

    private final QueryBuilder filter;

    static final NotQueryBuilder PROTOTYPE = new NotQueryBuilder();

    public NotQueryBuilder(QueryBuilder filter) {
        this.filter = Objects.requireNonNull(filter);
    }

    private NotQueryBuilder() {
        this.filter = null;
    }

    /**
     * @return the filter added to "not".
     */
    public QueryBuilder filter() {
        return this.filter;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field("query");
        filter.toXContent(builder, params);
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    @Override
    protected Query doToQuery(QueryParseContext parseContext) throws IOException {
        Query luceneQuery = filter.toQuery(parseContext);
        if (luceneQuery == null) {
            return null;
        }
        return Queries.not(luceneQuery);
    }

    @Override
    public QueryValidationException validate() {
        return validateInnerQuery(filter, null);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(filter);
    }

    @Override
    protected boolean doEquals(NotQueryBuilder other) {
        return Objects.equals(filter, other.filter);
    }

    @Override
    protected NotQueryBuilder doReadFrom(StreamInput in) throws IOException {
        QueryBuilder queryBuilder = in.readNamedWriteable();
        return new NotQueryBuilder(queryBuilder);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeNamedWriteable(filter);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
