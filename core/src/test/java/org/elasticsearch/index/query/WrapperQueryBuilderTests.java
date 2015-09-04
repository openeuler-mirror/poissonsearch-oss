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
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

public class WrapperQueryBuilderTests extends BaseQueryTestCase<WrapperQueryBuilder> {

    @Override
    protected boolean supportsBoostAndQueryNameParsing() {
        return false;
    }

    @Override
    protected WrapperQueryBuilder doCreateTestQueryBuilder() {
        QueryBuilder wrappedQuery = RandomQueryBuilder.createQuery(random());
        switch (randomInt(2)) {
            case 0:
                return new WrapperQueryBuilder(wrappedQuery.toString());
            case 1:
                return new WrapperQueryBuilder(wrappedQuery.buildAsBytes().toBytes());
            case 2:
                return new WrapperQueryBuilder(wrappedQuery.buildAsBytes());
            default:
                throw new UnsupportedOperationException();
        }
    }

    @Override
    protected void doAssertLuceneQuery(WrapperQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        try (XContentParser qSourceParser = XContentFactory.xContent(queryBuilder.source()).createParser(queryBuilder.source())) {
            final QueryShardContext contextCopy = new QueryShardContext(context.index(), context.indexQueryParserService());
            contextCopy.reset(qSourceParser);
            QueryBuilder<?> innerQuery = contextCopy.parseContext().parseInnerQueryBuilder();
            Query expected = innerQuery.toQuery(context);
            if (expected != null && queryBuilder.boost() != AbstractQueryBuilder.DEFAULT_BOOST) {
                expected.setBoost(queryBuilder.boost());
            }
            assertThat(query, equalTo(expected));
        }
    }

    @Override
    protected void assertBoost(WrapperQueryBuilder queryBuilder, Query query) throws IOException {
        //nothing to do here, boost check is already included in equality check done as part of doAssertLuceneQuery above
    }

    @Test
    public void testValidate() {
        WrapperQueryBuilder wrapperQueryBuilder = new WrapperQueryBuilder((byte[]) null);
        assertThat(wrapperQueryBuilder.validate().validationErrors().size(), is(1));

        wrapperQueryBuilder = new WrapperQueryBuilder("");
        assertThat(wrapperQueryBuilder.validate().validationErrors().size(), is(1));
    }
}
