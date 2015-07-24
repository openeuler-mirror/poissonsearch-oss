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

import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.spans.SpanMultiTermQueryWrapper;
import org.junit.Test;

import java.io.IOException;

public class SpanMultiTermQueryBuilderTest extends BaseQueryTestCase<SpanMultiTermQueryBuilder> {

    @Override
    protected Query doCreateExpectedQuery(SpanMultiTermQueryBuilder testQueryBuilder, QueryParseContext context) throws IOException {
        Query multiTermQuery = testQueryBuilder.multiTermQueryBuilder().toQuery(context);
        return new SpanMultiTermQueryWrapper<>((MultiTermQuery) multiTermQuery);
    }

    @Override
    protected SpanMultiTermQueryBuilder doCreateTestQueryBuilder() {
        MultiTermQueryBuilder multiTermQueryBuilder = RandomQueryBuilder.createMultiTermQuery(random());
        return new SpanMultiTermQueryBuilder(multiTermQueryBuilder);
    }

    @Test
    public void testValidate() {
        int totalExpectedErrors = 0;
        MultiTermQueryBuilder multiTermQueryBuilder;
        if (randomBoolean()) {
            if (randomBoolean()) {
                multiTermQueryBuilder = new RangeQueryBuilder("");
            } else {
                multiTermQueryBuilder = null;
            }
            totalExpectedErrors++;
        } else {
            multiTermQueryBuilder = new RangeQueryBuilder("field");
        }
        SpanMultiTermQueryBuilder queryBuilder = new SpanMultiTermQueryBuilder(multiTermQueryBuilder);
        assertValidate(queryBuilder, totalExpectedErrors);
    }

    /**
     * test checks that we throw an {@link UnsupportedOperationException} if the query wrapped
     * by {@link SpanMultiTermQueryBuilder} does not generate a lucene {@link MultiTermQuery}.
     * This is currently the case for {@link RangeQueryBuilder} when the target field is mapped
     * to a date.
     */
    @Test
    public void testUnsupportedInnerQueryType() throws IOException {
        QueryParseContext parseContext = createContext();
        // test makes only sense if we have at least one type registered with date field mapping
        if (getCurrentTypes().length > 0 && parseContext.fieldMapper(DATE_FIELD_NAME) != null) {
            try {
                RangeQueryBuilder query = new RangeQueryBuilder(DATE_FIELD_NAME);
                new SpanMultiTermQueryBuilder(query).toQuery(createContext());
                fail("Exception expected, range query on date fields should not generate a lucene " + MultiTermQuery.class.getName());
            } catch (UnsupportedOperationException e) {
                assert(e.getMessage().contains("unsupported inner query, should be " + MultiTermQuery.class.getName()));
            }
        }
    }
}
