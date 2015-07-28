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

import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;

public class ConstantScoreQueryBuilderTest extends BaseQueryTestCase<ConstantScoreQueryBuilder> {

    /**
     * @return a {@link ConstantScoreQueryBuilder} with random boost between 0.1f and 2.0f
     */
    @Override
    protected ConstantScoreQueryBuilder doCreateTestQueryBuilder() {
        return new ConstantScoreQueryBuilder(RandomQueryBuilder.createQuery(random()));
    }

    @Override
    protected void doAssertLuceneQuery(ConstantScoreQueryBuilder queryBuilder, Query query, QueryParseContext context) throws IOException {
        Query innerQuery = queryBuilder.query().toQuery(context);
        if (innerQuery == null) {
            assertThat(query, nullValue());
        } else {
            assertThat(query, instanceOf(ConstantScoreQuery.class));
            ConstantScoreQuery constantScoreQuery = (ConstantScoreQuery) query;
            assertThat(constantScoreQuery.getQuery(), equalTo(innerQuery));
        }
    }

    /**
     * test that missing "filter" element causes {@link QueryParsingException}
     */
    @Test(expected=QueryParsingException.class)
    public void testFilterElement() throws IOException {
        QueryParseContext context = createContext();
        String queryId = ConstantScoreQueryBuilder.PROTOTYPE.getName();
        String queryString = "{ \""+queryId+"\" : {}";
        XContentParser parser = XContentFactory.xContent(queryString).createParser(queryString);
        context.reset(parser);
        assertQueryHeader(parser, queryId);
        context.indexQueryParserService().queryParser(queryId).fromXContent(context);
    }

    @Test
    public void testValidate() {
        QueryBuilder innerQuery = null;
        int totalExpectedErrors = 0;
        if (randomBoolean()) {
            if (randomBoolean()) {
                innerQuery = RandomQueryBuilder.createInvalidQuery(random());
            }
            totalExpectedErrors++;
        } else {
            innerQuery = RandomQueryBuilder.createQuery(random());
        }
        ConstantScoreQueryBuilder constantScoreQuery = new ConstantScoreQueryBuilder(innerQuery);
        assertValidate(constantScoreQuery, totalExpectedErrors);
    }
}
