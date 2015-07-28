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
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.index.mapper.MappedFieldType;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;

public class TermQueryBuilderTest extends BaseTermQueryTestCase<TermQueryBuilder> {

    /**
     * @return a TermQuery with random field name and value, optional random boost and queryname
     */
    @Override
    protected TermQueryBuilder createQueryBuilder(String fieldName, Object value) {
        return new TermQueryBuilder(fieldName, value);
    }

    @Override
    protected void doAssertLuceneQuery(TermQueryBuilder queryBuilder, Query query, QueryParseContext context) throws IOException {
        assertThat(query, instanceOf(TermQuery.class));
        TermQuery termQuery = (TermQuery) query;
        assertThat(termQuery.getTerm().field(), equalTo(queryBuilder.fieldName()));
        MappedFieldType mapper = context.fieldMapper(queryBuilder.fieldName());
        if (mapper != null) {
            BytesRef bytesRef = mapper.indexedValueForSearch(queryBuilder.value());
            assertThat(termQuery.getTerm().bytes(), equalTo(bytesRef));
        } else {
            assertThat(termQuery.getTerm().bytes(), equalTo(BytesRefs.toBytesRef(queryBuilder.value())));
        }
    }
}
