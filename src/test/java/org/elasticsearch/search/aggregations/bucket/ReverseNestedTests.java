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
package org.elasticsearch.search.aggregations.bucket;

import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.search.aggregations.bucket.nested.Nested;
import org.elasticsearch.search.aggregations.bucket.nested.ReverseNested;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.search.aggregations.AggregationBuilders.*;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchResponse;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsNull.notNullValue;

/**
 *
 */
@ElasticsearchIntegrationTest.SuiteScopeTest
public class ReverseNestedTests extends ElasticsearchIntegrationTest {

    public void setupSuiteScopeCluster() throws Exception {
        assertAcked(prepareCreate("idx")
                .addMapping(
                        "type1",
                        jsonBuilder().startObject().startObject("properties")
                                .startObject("field1").field("type", "string").endObject()
                                .startObject("nested1").field("type", "nested").startObject("properties")
                                    .startObject("field2").field("type", "string").endObject()
                                .endObject().endObject()
                                .endObject().endObject()
                )
                .addMapping(
                        "type2",
                        jsonBuilder().startObject().startObject("properties")
                                .startObject("nested1").field("type", "nested").startObject("properties")
                                    .startObject("field1").field("type", "string").endObject()
                                        .startObject("nested2").field("type", "nested").startObject("properties")
                                            .startObject("field2").field("type", "string").endObject()
                                        .endObject().endObject()
                                    .endObject().endObject()
                                .endObject().endObject()
                )
        );

        insertDocs(Arrays.asList("a", "b", "c"), Arrays.asList("1", "2", "3", "4"));
        insertDocs(Arrays.asList("b", "c", "d"), Arrays.asList("4", "5", "6", "7"));
        insertDocs(Arrays.asList("c", "d", "e"), Arrays.asList("7", "8", "9", "1"));
        refresh();
        insertDocs(Arrays.asList("a", "e"), Arrays.asList("7", "4", "1", "1"));
        insertDocs(Arrays.asList("a", "c"), Arrays.asList("2", "1"));
        insertDocs(Arrays.asList("a"), Arrays.asList("3", "4"));
        refresh();
        insertDocs(Arrays.asList("x", "c"), Arrays.asList("1", "8"));
        insertDocs(Arrays.asList("y", "c"), Arrays.asList("6"));
        insertDocs(Arrays.asList("z"), Arrays.asList("5", "9"));
        refresh();

        ensureSearchable();
    }

    private void insertDocs(List<String> values1, List<String> values2) throws Exception {
        XContentBuilder source = jsonBuilder()
                .startObject()
                .array("field1", values1.toArray())
                .startArray("nested1");
        for (String value1 : values2) {
            source.startObject().field("field2", value1).endObject();
        }
        source.endArray().endObject();
        indexRandom(false, client().prepareIndex("idx", "type1").setRouting("1").setSource(source));

        source = jsonBuilder()
                .startObject()
                .field("x", "y")
                .startArray("nested1").startObject()
                    .array("field1", values1.toArray())
                        .startArray("nested2");
        for (String value1 : values2) {
            source.startObject().field("field2", value1).endObject();
        }
        source.endArray().endObject().endArray().endObject();
        indexRandom(false, client().prepareIndex("idx", "type2").setRouting("1").setSource(source));
    }

    @Test
    public void simple_reverseNestedToRoot() throws Exception {
        SearchResponse response = client().prepareSearch("idx").setTypes("type1")
                .addAggregation(nested("nested1").path("nested1")
                        .subAggregation(
                                terms("field2").field("nested1.field2")
                                        .subAggregation(
                                                reverseNested("nested1_to_field1")
                                                        .subAggregation(
                                                                terms("field1").field("field1")
                                                        )
                                        )
                        )
                ).get();

        verifyResults(response);
    }

    @Test
    public void simple_reverseNestedToNested1() throws Exception {
        SearchResponse response = client().prepareSearch("idx")
                .addAggregation(nested("nested1").path("nested1.nested2")
                        .subAggregation(
                                terms("field2").field("nested1.nested2.field2")
                                        .subAggregation(
                                                reverseNested("nested1_to_field1").path("nested1")
                                                        .subAggregation(
                                                                terms("field1").field("nested1.field1")
                                                        )
                                        )
                        )
                ).get();
        verifyResults(response);
    }

    @Test(expected = SearchPhaseExecutionException.class)
    public void testReverseNestedAggWithoutNestedAgg() throws Exception {
        client().prepareSearch("idx")
                .addAggregation(terms("field2").field("nested1.nested2.field2")
                                .subAggregation(
                                        reverseNested("nested1_to_field1")
                                                .subAggregation(
                                                        terms("field1").field("nested1.field1")
                                                )
                                )
                ).get();
    }

    private void verifyResults(SearchResponse response) {
        assertSearchResponse(response);

        Nested nested = response.getAggregations().get("nested1");
        assertThat(nested, notNullValue());
        assertThat(nested.getName(), equalTo("nested1"));
        assertThat(nested.getDocCount(), equalTo(25l));
        assertThat(nested.getAggregations().asList().isEmpty(), is(false));

        Terms usernames = nested.getAggregations().get("field2");
        assertThat(usernames, notNullValue());
        assertThat(usernames.getBuckets().size(), equalTo(9));
        List<Terms.Bucket> usernameBuckets = new ArrayList<>(usernames.getBuckets());

        // nested.field2: 1
        Terms.Bucket bucket = usernameBuckets.get(0);
        assertThat(bucket.getKey(), equalTo("1"));
        assertThat(bucket.getDocCount(), equalTo(6l));
        ReverseNested reverseNested = bucket.getAggregations().get("nested1_to_field1");
        Terms tags = reverseNested.getAggregations().get("field1");
        List<Terms.Bucket> tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(6));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(4l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("a"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(3l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("e"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(3).getKey(), equalTo("b"));
        assertThat(tagsBuckets.get(3).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(4).getKey(), equalTo("d"));
        assertThat(tagsBuckets.get(4).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(5).getKey(), equalTo("x"));
        assertThat(tagsBuckets.get(5).getDocCount(), equalTo(1l));

        // nested.field2: 4
        bucket = usernameBuckets.get(1);
        assertThat(bucket.getKey(), equalTo("4"));
        assertThat(bucket.getDocCount(), equalTo(4l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(5));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("a"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(3l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("b"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(3).getKey(), equalTo("d"));
        assertThat(tagsBuckets.get(3).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(4).getKey(), equalTo("e"));
        assertThat(tagsBuckets.get(4).getDocCount(), equalTo(1l));

        // nested.field2: 7
        bucket = usernameBuckets.get(2);
        assertThat(bucket.getKey(), equalTo("7"));
        assertThat(bucket.getDocCount(), equalTo(3l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(5));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("d"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("e"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(3).getKey(), equalTo("a"));
        assertThat(tagsBuckets.get(3).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(4).getKey(), equalTo("b"));
        assertThat(tagsBuckets.get(4).getDocCount(), equalTo(1l));

        // nested.field2: 2
        bucket = usernameBuckets.get(3);
        assertThat(bucket.getKey(), equalTo("2"));
        assertThat(bucket.getDocCount(), equalTo(2l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(3));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("a"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("b"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(1l));

        // nested.field2: 3
        bucket = usernameBuckets.get(4);
        assertThat(bucket.getKey(), equalTo("3"));
        assertThat(bucket.getDocCount(), equalTo(2l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(3));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("a"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("b"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(1l));

        // nested.field2: 5
        bucket = usernameBuckets.get(5);
        assertThat(bucket.getKey(), equalTo("5"));
        assertThat(bucket.getDocCount(), equalTo(2l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(4));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("b"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("d"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(3).getKey(), equalTo("z"));
        assertThat(tagsBuckets.get(3).getDocCount(), equalTo(1l));

        // nested.field2: 6
        bucket = usernameBuckets.get(6);
        assertThat(bucket.getKey(), equalTo("6"));
        assertThat(bucket.getDocCount(), equalTo(2l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(4));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("b"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("d"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(3).getKey(), equalTo("y"));
        assertThat(tagsBuckets.get(3).getDocCount(), equalTo(1l));

        // nested.field2: 8
        bucket = usernameBuckets.get(7);
        assertThat(bucket.getKey(), equalTo("8"));
        assertThat(bucket.getDocCount(), equalTo(2l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(4));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(2l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("d"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("e"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(3).getKey(), equalTo("x"));
        assertThat(tagsBuckets.get(3).getDocCount(), equalTo(1l));

        // nested.field2: 9
        bucket = usernameBuckets.get(8);
        assertThat(bucket.getKey(), equalTo("9"));
        assertThat(bucket.getDocCount(), equalTo(2l));
        reverseNested = bucket.getAggregations().get("nested1_to_field1");
        tags = reverseNested.getAggregations().get("field1");
        tagsBuckets = new ArrayList<>(tags.getBuckets());
        assertThat(tagsBuckets.size(), equalTo(4));
        assertThat(tagsBuckets.get(0).getKey(), equalTo("c"));
        assertThat(tagsBuckets.get(0).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(1).getKey(), equalTo("d"));
        assertThat(tagsBuckets.get(1).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(2).getKey(), equalTo("e"));
        assertThat(tagsBuckets.get(2).getDocCount(), equalTo(1l));
        assertThat(tagsBuckets.get(3).getKey(), equalTo("z"));
        assertThat(tagsBuckets.get(3).getDocCount(), equalTo(1l));
    }
}
