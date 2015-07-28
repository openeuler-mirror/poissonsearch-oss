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
import org.apache.lucene.search.RegexpQuery;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class RegexpQueryBuilderTest extends BaseQueryTestCase<RegexpQueryBuilder> {

    @Override
    protected RegexpQueryBuilder doCreateTestQueryBuilder() {
        // mapped or unmapped fields
        String fieldName = randomBoolean() ? STRING_FIELD_NAME : randomAsciiOfLengthBetween(1, 10);
        String value = randomAsciiOfLengthBetween(1, 10);
        RegexpQueryBuilder query = new RegexpQueryBuilder(fieldName, value);

        if (randomBoolean()) {
            List<RegexpFlag> flags = new ArrayList<>();
            int iter = randomInt(5);
            for (int i = 0; i < iter; i++) {
                flags.add(randomFrom(RegexpFlag.values()));    
            }
            query.flags(flags.toArray(new RegexpFlag[flags.size()]));
        }
        if (randomBoolean()) {
            query.maxDeterminizedStates(randomInt(50000));
        }
        if (randomBoolean()) {
            query.rewrite(randomFrom(getRandomRewriteMethod()));
        }
        return query;
    }

    @Override
    protected void doAssertLuceneQuery(RegexpQueryBuilder queryBuilder, Query query, QueryParseContext context) throws IOException {
        assertThat(query, instanceOf(RegexpQuery.class));
    }

    @Test
    public void testValidate() {
        RegexpQueryBuilder regexQueryBuilder = new RegexpQueryBuilder("", "regex");
        assertThat(regexQueryBuilder.validate().validationErrors().size(), is(1));

        regexQueryBuilder = new RegexpQueryBuilder("field", null);
        assertThat(regexQueryBuilder.validate().validationErrors().size(), is(1));

        regexQueryBuilder = new RegexpQueryBuilder("field", "regex");
        assertNull(regexQueryBuilder.validate());

        regexQueryBuilder = new RegexpQueryBuilder(null, null);
        assertThat(regexQueryBuilder.validate().validationErrors().size(), is(2));
    }
}
