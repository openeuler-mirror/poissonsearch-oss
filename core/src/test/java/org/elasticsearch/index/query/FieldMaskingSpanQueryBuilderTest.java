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
import org.elasticsearch.index.mapper.MappedFieldType;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;

public class FieldMaskingSpanQueryBuilderTest extends BaseQueryTestCase<FieldMaskingSpanQueryBuilder> {

    @Override
    protected FieldMaskingSpanQueryBuilder doCreateTestQueryBuilder() {
        String fieldName;
        if (randomBoolean()) {
            fieldName = randomFrom(mappedFieldNames);
        } else {
            fieldName = randomAsciiOfLengthBetween(1, 10);
        }
        SpanTermQueryBuilder innerQuery = new SpanTermQueryBuilderTest().createTestQueryBuilder();
        return new FieldMaskingSpanQueryBuilder(innerQuery, fieldName);
    }

    @Override
    protected void doAssertLuceneQuery(FieldMaskingSpanQueryBuilder queryBuilder, Query query, QueryParseContext context) throws IOException {
        String fieldInQuery = queryBuilder.fieldName();
        MappedFieldType fieldType = context.fieldMapper(fieldInQuery);
        if (fieldType != null) {
            fieldInQuery = fieldType.names().indexName();
        }
        assertThat(query, instanceOf(FieldMaskingSpanQuery.class));
        FieldMaskingSpanQuery fieldMaskingSpanQuery = (FieldMaskingSpanQuery) query;
        assertThat(fieldMaskingSpanQuery.getField(), equalTo(fieldInQuery));
        assertThat(fieldMaskingSpanQuery.getMaskedQuery(), equalTo(queryBuilder.innerQuery().toQuery(context)));
    }

    @Test
    public void testValidate() {
        String fieldName = null;
        SpanQueryBuilder spanQueryBuilder = null;
        int totalExpectedErrors = 0;
        if (randomBoolean()) {
            fieldName = "fieldName";
        } else {
            if (randomBoolean()) {
                fieldName = "";
            }
            totalExpectedErrors++;
        }
        if (randomBoolean()) {
            if (randomBoolean()) {
                spanQueryBuilder = new SpanTermQueryBuilder("", "test");
            }
            totalExpectedErrors++;
        } else {
            spanQueryBuilder = new SpanTermQueryBuilder("name", "value");
        }
        FieldMaskingSpanQueryBuilder queryBuilder = new FieldMaskingSpanQueryBuilder(spanQueryBuilder, fieldName);
        assertValidate(queryBuilder, totalExpectedErrors);
    }
}
