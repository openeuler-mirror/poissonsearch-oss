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


import org.apache.lucene.queries.TermsQuery;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.junit.Test;

import java.io.IOException;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.instanceOf;

public class IdsQueryBuilderTest extends BaseQueryTestCase<IdsQueryBuilder> {

    /**
     * check that parser throws exception on missing values field
     * @throws IOException
     */
    @Test(expected=QueryParsingException.class)
    public void testIdsNotProvided() throws IOException {
        String noIdsFieldQuery = "{\"ids\" : { \"type\" : \"my_type\"  }";
        XContentParser parser = XContentFactory.xContent(noIdsFieldQuery).createParser(noIdsFieldQuery);
        QueryParseContext context = createContext();
        context.reset(parser);
        assertQueryHeader(parser, "ids");
        context.indexQueryParserService().queryParser("ids").fromXContent(context);
    }

    @Override
    protected IdsQueryBuilder doCreateTestQueryBuilder() {
        String[] types;
        if (getCurrentTypes().length > 0 && randomBoolean()) {
            int numberOfTypes = randomIntBetween(1, getCurrentTypes().length);
            types = new String[numberOfTypes];
            for (int i = 0; i < numberOfTypes; i++) {
                if (frequently()) {
                    types[i] = randomFrom(getCurrentTypes());
                } else {
                    types[i] = randomAsciiOfLengthBetween(1, 10);
                }
            }
        } else {
            if (randomBoolean()) {
                types = new String[]{MetaData.ALL};
            } else {
                types = new String[0];
            }
        }
        int numberOfIds = randomIntBetween(0, 10);
        String[] ids = new String[numberOfIds];
        for (int i = 0; i < numberOfIds; i++) {
            ids[i] = randomAsciiOfLengthBetween(1, 10);
        }
        IdsQueryBuilder query;
        if (types.length > 0 || randomBoolean()) {
            query = new IdsQueryBuilder(types);
            query.addIds(ids);
        } else {
            query = new IdsQueryBuilder();
            query.addIds(ids);
        }
        return query;
    }

    @Override
    protected void doAssertLuceneQuery(IdsQueryBuilder queryBuilder, Query query, QueryParseContext context) throws IOException {
        if (queryBuilder.ids().size() == 0) {
            assertThat(query, instanceOf(BooleanQuery.class));
            assertThat(((BooleanQuery)query).clauses().size(), equalTo(0));
        } else {
            assertThat(query, instanceOf(TermsQuery.class));
        }
    }
}
