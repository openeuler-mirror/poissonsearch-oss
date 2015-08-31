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
import org.apache.lucene.search.spans.SpanNotQuery;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.junit.Test;

import java.io.IOException;

import static org.elasticsearch.index.query.QueryBuilders.spanNearQuery;
import static org.elasticsearch.index.query.QueryBuilders.spanTermQuery;
import static org.hamcrest.Matchers.*;

public class SpanNotQueryBuilderTest extends BaseQueryTestCase<SpanNotQueryBuilder> {

    @Override
    protected SpanNotQueryBuilder doCreateTestQueryBuilder() {
        SpanTermQueryBuilder[] spanTermQueries = new SpanTermQueryBuilderTest().createSpanTermQueryBuilders(2);
        SpanNotQueryBuilder queryBuilder = new SpanNotQueryBuilder(spanTermQueries[0], spanTermQueries[1]);
        if (randomBoolean()) {
            // also test negative values, they should implicitly be changed to 0
            queryBuilder.dist(randomIntBetween(-2, 10));
        } else {
            if (randomBoolean()) {
                queryBuilder.pre(randomIntBetween(-2, 10));
            }
            if (randomBoolean()) {
                queryBuilder.post(randomIntBetween(-2, 10));
            }
        }
        return queryBuilder;
    }

    @Override
    protected void doAssertLuceneQuery(SpanNotQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        assertThat(query, instanceOf(SpanNotQuery.class));
        SpanNotQuery spanNotQuery = (SpanNotQuery) query;
        assertThat(spanNotQuery.getExclude(), equalTo(queryBuilder.excludeQuery().toQuery(context)));
        assertThat(spanNotQuery.getInclude(), equalTo(queryBuilder.includeQuery().toQuery(context)));
    }

    @Test
    public void testValidate() {
        int totalExpectedErrors = 0;
        SpanQueryBuilder include;
        if (randomBoolean()) {
            if (randomBoolean()) {
                include = new SpanTermQueryBuilder("", "test");
            } else {
                include = null;
            }
            totalExpectedErrors++;
        } else {
            include = new SpanTermQueryBuilder("name", "value");
        }
        SpanQueryBuilder exclude;
        if (randomBoolean()) {
            if (randomBoolean()) {
                exclude = new SpanTermQueryBuilder("", "test");
            } else {
                exclude = null;
            }
            totalExpectedErrors++;
        } else {
            exclude = new SpanTermQueryBuilder("name", "value");
        }
        SpanNotQueryBuilder queryBuilder = new SpanNotQueryBuilder(include, exclude);
        assertValidate(queryBuilder, totalExpectedErrors);
    }

    @Test
    public void testDist() {
        SpanNotQueryBuilder builder = new SpanNotQueryBuilder(new SpanTermQueryBuilder("name1", "value1"), new SpanTermQueryBuilder("name2", "value2"));
        assertThat(builder.pre(), equalTo(0));
        assertThat(builder.post(), equalTo(0));
        builder.dist(-4);
        assertThat(builder.pre(), equalTo(0));
        assertThat(builder.post(), equalTo(0));
        builder.dist(4);
        assertThat(builder.pre(), equalTo(4));
        assertThat(builder.post(), equalTo(4));
    }

    @Test
    public void testPrePost() {
        SpanNotQueryBuilder builder = new SpanNotQueryBuilder(new SpanTermQueryBuilder("name1", "value1"), new SpanTermQueryBuilder("name2", "value2"));
        assertThat(builder.pre(), equalTo(0));
        assertThat(builder.post(), equalTo(0));
        builder.pre(-4).post(-4);
        assertThat(builder.pre(), equalTo(0));
        assertThat(builder.post(), equalTo(0));
        builder.pre(1).post(2);
        assertThat(builder.pre(), equalTo(1));
        assertThat(builder.post(), equalTo(2));
    }

    /**
     * test correct parsing of `dist` parameter, this should create builder with pre/post set to same value
     */
    @Test
    public void testParseDist() throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject();
        builder.startObject(SpanNotQueryBuilder.NAME);
        builder.field("exclude");
        spanTermQuery("description", "jumped").toXContent(builder, null);
        builder.field("include");
        spanNearQuery(1).clause(QueryBuilders.spanTermQuery("description", "quick"))
                .clause(QueryBuilders.spanTermQuery("description", "fox")).toXContent(builder, null);
        builder.field("dist", 3);
        builder.endObject();
        builder.endObject();
        SpanNotQueryBuilder query = (SpanNotQueryBuilder)parseQuery(builder.string());
        assertThat(query.pre(), equalTo(3));
        assertThat(query.post(), equalTo(3));
        assertNotNull(query.includeQuery());
        assertNotNull(query.excludeQuery());
    }

    /**
     * test exceptions for three types of broken json, missing include / exclude and both dist and pre/post specified
     */
    @Test
    public void testParserExceptions() throws IOException {

        {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            builder.startObject(SpanNotQueryBuilder.NAME);
            builder.field("exclude");
            spanTermQuery("description", "jumped").toXContent(builder, null);
            builder.field("dist", 2);
            builder.endObject();
            builder.endObject();

            try {
                parseQuery(builder.string());
                fail("QueryParsingException should have been caught");
            } catch (QueryParsingException e) {
                assertThat("QueryParsingException should have been caught", e.getDetailedMessage(), containsString("spanNot must have [include]"));
            }
        }

        {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            builder.startObject(SpanNotQueryBuilder.NAME);
            builder.field("include");
            spanNearQuery(1).clause(QueryBuilders.spanTermQuery("description", "quick"))
                    .clause(QueryBuilders.spanTermQuery("description", "fox")).toXContent(builder, null);
            builder.field("dist", 2);
            builder.endObject();
            builder.endObject();

            try {
                parseQuery(builder.string());
                fail("QueryParsingException should have been caught");
            } catch (QueryParsingException e) {
                assertThat("QueryParsingException should have been caught", e.getDetailedMessage(), containsString("spanNot must have [exclude]"));
            }
        }

        {
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            builder.startObject(SpanNotQueryBuilder.NAME);
            builder.field("include");
            spanNearQuery(1).clause(QueryBuilders.spanTermQuery("description", "quick"))
                    .clause(QueryBuilders.spanTermQuery("description", "fox")).toXContent(builder, null);
            builder.field("exclude");
            spanTermQuery("description", "jumped").toXContent(builder, null);
            builder.field("dist", 2);
            builder.field("pre", 2);
            builder.endObject();
            builder.endObject();

            try {
                parseQuery(builder.string());
                fail("QueryParsingException should have been caught");
            } catch (QueryParsingException e) {
                assertThat("QueryParsingException should have been caught", e.getDetailedMessage(), containsString("spanNot can either use [dist] or [pre] & [post] (or none)"));
            }
        }
    }
}
