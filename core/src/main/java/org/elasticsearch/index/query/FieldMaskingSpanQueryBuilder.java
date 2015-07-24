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
import org.apache.lucene.search.spans.FieldMaskingSpanQuery;
import org.apache.lucene.search.spans.SpanQuery;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.MappedFieldType;

import java.io.IOException;
import java.util.Objects;

public class FieldMaskingSpanQueryBuilder extends AbstractQueryBuilder<FieldMaskingSpanQueryBuilder> implements SpanQueryBuilder<FieldMaskingSpanQueryBuilder>{

    public static final String NAME = "field_masking_span";

    private final SpanQueryBuilder queryBuilder;

    private final String fieldName;

    static final FieldMaskingSpanQueryBuilder PROTOTYPE = new FieldMaskingSpanQueryBuilder(null, null);

    /**
     * Constructs a new {@link FieldMaskingSpanQueryBuilder} given an inner {@link SpanQueryBuilder} for
     * a given field
     * @param queryBuilder inner {@link SpanQueryBuilder}
     * @param fieldName the field name
     */
    public FieldMaskingSpanQueryBuilder(SpanQueryBuilder queryBuilder, String fieldName) {
        this.queryBuilder = queryBuilder;
        this.fieldName = fieldName;
    }

    /**
     * @return the field name for this query
     */
    public String fieldName() {
        return this.fieldName;
    }

    /**
     * @return the inner {@link QueryBuilder}
     */
    public SpanQueryBuilder innerQuery() {
        return this.queryBuilder;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field("query");
        queryBuilder.toXContent(builder, params);
        builder.field("field", fieldName);
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    @Override
    protected SpanQuery doToQuery(QueryParseContext parseContext) throws IOException {
        String fieldInQuery = fieldName;
        MappedFieldType fieldType = parseContext.fieldMapper(fieldName);
        if (fieldType != null) {
            fieldInQuery = fieldType.names().indexName();
        }
        Query innerQuery = queryBuilder.toQuery(parseContext);
        assert innerQuery instanceof SpanQuery;
        return new FieldMaskingSpanQuery((SpanQuery)innerQuery, fieldInQuery);
    }

    @Override
    public QueryValidationException validate() {
        QueryValidationException validationException = null;
        if (queryBuilder == null) {
            validationException = addValidationError("inner clause [query] cannot be null.", validationException);
        } else {
            validationException = validateInnerQuery(queryBuilder, validationException);
        }
        if (fieldName == null || fieldName.isEmpty()) {
            validationException = addValidationError("field name is null or empty", validationException);
        }
        return validationException;
    }

    @Override
    protected FieldMaskingSpanQueryBuilder doReadFrom(StreamInput in) throws IOException {
        QueryBuilder innerQueryBuilder = in.readNamedWriteable();
        return new FieldMaskingSpanQueryBuilder((SpanQueryBuilder) innerQueryBuilder, in.readString());
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeNamedWriteable(queryBuilder);
        out.writeString(fieldName);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(queryBuilder, fieldName);
    }

    @Override
    protected boolean doEquals(FieldMaskingSpanQueryBuilder other) {
        return Objects.equals(queryBuilder, other.queryBuilder) &&
               Objects.equals(fieldName, other.fieldName);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
