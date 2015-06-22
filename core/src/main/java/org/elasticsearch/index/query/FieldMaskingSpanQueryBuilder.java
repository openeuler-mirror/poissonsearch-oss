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

import org.apache.lucene.search.spans.SpanQuery;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;

public class FieldMaskingSpanQueryBuilder extends AbstractQueryBuilder<FieldMaskingSpanQueryBuilder> implements SpanQueryBuilder<FieldMaskingSpanQueryBuilder>, BoostableQueryBuilder<FieldMaskingSpanQueryBuilder> {

    public static final String NAME = "field_masking_span";

    private final SpanQueryBuilder queryBuilder;

    private final String field;

    private float boost = -1;

    private String queryName;

    static final FieldMaskingSpanQueryBuilder PROTOTYPE = new FieldMaskingSpanQueryBuilder(null, null);

    public FieldMaskingSpanQueryBuilder(SpanQueryBuilder queryBuilder, String field) {
        this.queryBuilder = queryBuilder;
        this.field = field;
    }

    @Override
    public FieldMaskingSpanQueryBuilder boost(float boost) {
        this.boost = boost;
        return this;
    }

    /**
     * Sets the query name for the filter that can be used when searching for matched_filters per hit.
     */
    public FieldMaskingSpanQueryBuilder queryName(String queryName) {
        this.queryName = queryName;
        return this;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field("query");
        queryBuilder.toXContent(builder, params);
        builder.field("field", field);
        if (boost != -1) {
            builder.field("boost", boost);
        }
        if (queryName != null) {
            builder.field("_name", queryName);
        }
        builder.endObject();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public SpanQuery toQuery(QueryParseContext parseContext) throws QueryParsingException, IOException {
        //norelease just a temporary implementation, will go away once this query is refactored and properly overrides toQuery
        return (SpanQuery)super.toQuery(parseContext);
    }
}
