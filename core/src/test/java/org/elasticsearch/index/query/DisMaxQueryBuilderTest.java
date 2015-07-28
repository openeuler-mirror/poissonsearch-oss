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

import org.apache.lucene.search.DisjunctionMaxQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Iterator;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.nullValue;

public class DisMaxQueryBuilderTest extends BaseQueryTestCase<DisMaxQueryBuilder> {

    /**
     * @return a {@link DisMaxQueryBuilder} with random inner queries
     */
    @Override
    protected DisMaxQueryBuilder doCreateTestQueryBuilder() {
        DisMaxQueryBuilder dismax = new DisMaxQueryBuilder();
        int clauses = randomIntBetween(1, 5);
        for (int i = 0; i < clauses; i++) {
            dismax.add(RandomQueryBuilder.createQuery(random()));
        }
        if (randomBoolean()) {
            dismax.tieBreaker(2.0f / randomIntBetween(1, 20));
        }
        return dismax;
    }

    @Override
    protected void doAssertLuceneQuery(DisMaxQueryBuilder queryBuilder, Query query, QueryParseContext context) throws IOException {
        Collection<Query> queries = AbstractQueryBuilder.toQueries(queryBuilder.queries(), context);
        if (queries.isEmpty()) {
            assertThat(query, nullValue());
        } else {
            assertThat(query, instanceOf(DisjunctionMaxQuery.class));
            DisjunctionMaxQuery disjunctionMaxQuery = (DisjunctionMaxQuery) query;
            assertThat(disjunctionMaxQuery.getTieBreakerMultiplier(), equalTo(queryBuilder.tieBreaker()));
            assertThat(disjunctionMaxQuery.getDisjuncts().size(), equalTo(queries.size()));
            Iterator<Query> queryIterator = queries.iterator();
            for (int i = 0; i < disjunctionMaxQuery.getDisjuncts().size(); i++) {
                assertThat(disjunctionMaxQuery.getDisjuncts().get(i), equalTo(queryIterator.next()));
            }
        }
    }

    /**
     * test `null`return value for missing inner queries
     * @throws IOException
     * @throws QueryParsingException
     */
    @Test
    public void testNoInnerQueries() throws QueryParsingException, IOException {
        DisMaxQueryBuilder disMaxBuilder = new DisMaxQueryBuilder();
        assertNull(disMaxBuilder.toQuery(createContext()));
        assertNull(disMaxBuilder.validate());
    }

    /**
     * Test inner query parsing to null. Current DSL allows inner filter element to parse to <tt>null</tt>.
     * Those should be ignored upstream. To test this, we use inner {@link ConstantScoreQueryBuilder}
     * with empty inner filter.
     */
    @Test
    public void testInnerQueryReturnsNull() throws IOException {
        QueryParseContext context = createContext();
        String queryId = ConstantScoreQueryBuilder.PROTOTYPE.getName();
        String queryString = "{ \""+queryId+"\" : { \"filter\" : { } }";
        XContentParser parser = XContentFactory.xContent(queryString).createParser(queryString);
        context.reset(parser);
        assertQueryHeader(parser, queryId);
        ConstantScoreQueryBuilder innerQueryBuilder = (ConstantScoreQueryBuilder) context.indexQueryParserService()
                .queryParser(queryId).fromXContent(context);

        DisMaxQueryBuilder disMaxBuilder = new DisMaxQueryBuilder().add(innerQueryBuilder);
        assertNull(disMaxBuilder.toQuery(context));
    }

    @Test
    public void testValidate() {
        DisMaxQueryBuilder disMaxQuery = new DisMaxQueryBuilder();
        int iters = randomIntBetween(0, 5);
        int totalExpectedErrors = 0;
        for (int i = 0; i < iters; i++) {
            if (randomBoolean()) {
                if (randomBoolean()) {
                    disMaxQuery.add(RandomQueryBuilder.createInvalidQuery(random()));
                } else {
                    disMaxQuery.add(null);
                }
                totalExpectedErrors++;
            } else {
                disMaxQuery.add(RandomQueryBuilder.createQuery(random()));
            }
        }
        assertValidate(disMaxQuery, totalExpectedErrors);
    }
}
