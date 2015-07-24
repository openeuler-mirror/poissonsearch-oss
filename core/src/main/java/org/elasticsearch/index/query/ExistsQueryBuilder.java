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

import org.apache.lucene.search.*;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.internal.FieldNamesFieldMapper;
import org.elasticsearch.index.mapper.object.ObjectMapper;

import java.io.IOException;
import java.util.Collection;
import java.util.Objects;

/**
 * Constructs a query that only match on documents that the field has a value in them.
 */
public class ExistsQueryBuilder extends AbstractQueryBuilder<ExistsQueryBuilder> {

    public static final String NAME = "exists";

    private final String name;

    static final ExistsQueryBuilder PROTOTYPE = new ExistsQueryBuilder(null);

    public ExistsQueryBuilder(String name) {
        this.name = name;
    }

    /**
     * @return the field name that has to exist for this query to match
     */
    public String name() {
        return this.name;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field("field", name);
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    @Override
    protected Query doToQuery(QueryParseContext parseContext) throws IOException {
        return newFilter(parseContext, name);
    }

    @Override
    public QueryValidationException validate() {
        // nothing to validate
        return null;
    }

    public static Query newFilter(QueryParseContext parseContext, String fieldPattern) {
        final FieldNamesFieldMapper.FieldNamesFieldType fieldNamesFieldType = (FieldNamesFieldMapper.FieldNamesFieldType)parseContext.mapperService().fullName(FieldNamesFieldMapper.NAME);
        if (fieldNamesFieldType == null) {
            // can only happen when no types exist, so no docs exist either
            return Queries.newMatchNoDocsQuery();
        }

        ObjectMapper objectMapper = parseContext.getObjectMapper(fieldPattern);
        if (objectMapper != null) {
            // automatic make the object mapper pattern
            fieldPattern = fieldPattern + ".*";
        }

        Collection<String> fields = parseContext.simpleMatchToIndexNames(fieldPattern);
        if (fields.isEmpty()) {
            // no fields exists, so we should not match anything
            return Queries.newMatchNoDocsQuery();
        }

        BooleanQuery boolFilter = new BooleanQuery();
        for (String field : fields) {
            MappedFieldType fieldType = parseContext.fieldMapper(field);
            Query filter = null;
            if (fieldNamesFieldType.isEnabled()) {
                final String f;
                if (fieldType != null) {
                    f = fieldType.names().indexName();
                } else {
                    f = field;
                }
                filter = fieldNamesFieldType.termQuery(f, parseContext);
            }
            // if _field_names are not indexed, we need to go the slow way
            if (filter == null && fieldType != null) {
                filter = fieldType.rangeQuery(null, null, true, true);
            }
            if (filter == null) {
                filter = new TermRangeQuery(field, null, null, true, true);
            }
            boolFilter.add(filter, BooleanClause.Occur.SHOULD);
        }
        return new ConstantScoreQuery(boolFilter);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(name);
    }

    @Override
    protected boolean doEquals(ExistsQueryBuilder other) {
        return Objects.equals(name, other.name);
    }

    @Override
    protected ExistsQueryBuilder doReadFrom(StreamInput in) throws IOException {
        return new ExistsQueryBuilder(in.readString());
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(name);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
