/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.integration.search.child;

import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.search.facet.terms.TermsFacet;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.*;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static org.elasticsearch.index.query.FilterBuilders.hasChildFilter;
import static org.elasticsearch.index.query.FilterBuilders.hasParentFilter;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.search.facet.FacetBuilders.termsFacet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 *
 */
public class SimpleChildQuerySearchTests extends AbstractNodesTests {

    private Client client;

    @BeforeClass
    public void createNodes() throws Exception {
        startNode("node1");
        startNode("node2");
        client = getClient();
    }

    @AfterClass
    public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    protected Client getClient() {
        return client("node1");
    }

    protected String getExecutionMethod() {
        return "uid";
    }

    @Test
    public void multiLevelChild() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("grandchild").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "child").endObject()
                .endObject().endObject()).execute().actionGet();

        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").execute().actionGet();
        client.prepareIndex("test", "child", "c1").setSource("c_field", "c_value1").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "grandchild", "gc1").setSource("gc_field", "gc_value1").setParent("c1").setRouting("gc1").execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch("test")
                .setQuery(
                        filteredQuery(
                                matchAllQuery(),
                                hasChildFilter(
                                        "child",
                                        filteredQuery(termQuery("c_field", "c_value1"), hasChildFilter("grandchild", termQuery("gc_field", "gc_value1")).executionType(getExecutionMethod()))
                                ).executionType(getExecutionMethod())
                        )
                )
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
    }

    @Test
    public void simpleChildQuery() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        // index simple data
        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").execute().actionGet();
        client.prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").execute().actionGet();
        client.prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").execute().actionGet();
        client.prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        // TEST FETCHING _parent from child
        SearchResponse searchResponse = client.prepareSearch("test")
                .setQuery(idsQuery("child").ids("c1"))
                .addFields("_parent")
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("c1"));
        assertThat(searchResponse.hits().getAt(0).field("_parent").value().toString(), equalTo("p1"));

        // TEST matching on parent
        searchResponse = client.prepareSearch("test")
                .setQuery(termQuery("child._parent", "p1"))
                .addFields("_parent")
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.hits().getAt(0).field("_parent").value().toString(), equalTo("p1"));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.hits().getAt(1).field("_parent").value().toString(), equalTo("p1"));

        searchResponse = client.prepareSearch("test")
                .setQuery(termQuery("_parent", "p1"))
                .addFields("_parent")
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.hits().getAt(0).field("_parent").value().toString(), equalTo("p1"));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.hits().getAt(1).field("_parent").value().toString(), equalTo("p1"));

        searchResponse = client.prepareSearch("test")
                .setQuery(queryString("_parent:p1"))
                .addFields("_parent")
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.hits().getAt(0).field("_parent").value().toString(), equalTo("p1"));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("c1"), equalTo("c2")));
        assertThat(searchResponse.hits().getAt(1).field("_parent").value().toString(), equalTo("p1"));


        // TOP CHILDREN QUERY

        searchResponse = client.prepareSearch("test")
                .setQuery(topChildrenQuery("child", termQuery("c_field", "yellow")))
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "blue"))).execute().actionGet();
        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "red"))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS CHILD QUERY

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod())).execute().actionGet();
        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "blue")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "red")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS CHILD FILTER

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "blue")).executionType(getExecutionMethod()))).execute().actionGet();
        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "red")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS PARENT FILTER
        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasParentFilter("parent", termQuery("p_field", "p_value2")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("c3"));
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("c4"));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasParentFilter("parent", termQuery("p_field", "p_value1")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("c1"));
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("c2"));

        // HAS PARENT QUERY
        searchResponse = client.prepareSearch("test").setQuery(hasParentQuery("parent", termQuery("p_field", "p_value2")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("c3"));
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("c4"));

        searchResponse = client.prepareSearch("test").setQuery(hasParentQuery("parent", termQuery("p_field", "p_value1")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("c1"));
        assertThat(searchResponse.hits().getAt(1).id(), equalTo("c2"));
    }

    @Test
    public void testHasParentFilter() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        Map<String, List<String>> parentToChildren = newHashMap();
        // Childless parent
        client.prepareIndex("test", "parent", "p0").setSource("p_field", "p0").execute().actionGet();
        parentToChildren.put("p0", new ArrayList<String>());

        String previousParentId = null;
        int numChildDocs = 32;
        int numChildDocsPerParent = 0;
        for (int i = 1; i <= numChildDocs; i++) {
            if (previousParentId == null || i % numChildDocsPerParent == 0) {
                previousParentId = "p" + i;
                client.prepareIndex("test", "parent", previousParentId).setSource("p_field", previousParentId).execute().actionGet();
                client.admin().indices().prepareFlush("test").execute().actionGet();
                numChildDocsPerParent++;
            }

            String childId = "c" + i;
            client.prepareIndex("test", "child", childId)
                    .setSource("c_field", childId)
                    .setParent(previousParentId)
                    .execute().actionGet();

            if (!parentToChildren.containsKey(previousParentId)) {
                parentToChildren.put(previousParentId, new ArrayList<String>());
            }
            parentToChildren.get(previousParentId).add(childId);
        }
        client.admin().indices().prepareRefresh().execute().actionGet();

        assertThat(parentToChildren.isEmpty(), equalTo(false));
        for (Map.Entry<String, List<String>> parentToChildrenEntry : parentToChildren.entrySet()) {
            SearchResponse searchResponse = client.prepareSearch("test")
                    .setQuery(constantScoreQuery(hasParentFilter("parent", termQuery("p_field", parentToChildrenEntry.getKey())).executionType(getExecutionMethod())))
                    .setSize(numChildDocsPerParent)
                    .execute().actionGet();

            assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
            assertThat(searchResponse.failedShards(), equalTo(0));
            List<String> childIds = parentToChildrenEntry.getValue();
            assertThat(searchResponse.hits().totalHits(), equalTo((long) childIds.size()));
            int counter = 0;
            for (String childId : childIds) {
                assertThat(searchResponse.hits().getAt(counter++).id(), equalTo(childId));
            }
        }
    }

    @Test
    public void simpleChildQueryWithFlush() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        // index simple data with flushes, so we have many segments
        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        // TOP CHILDREN QUERY

        SearchResponse searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "yellow"))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "blue"))).execute().actionGet();
        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "red"))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS CHILD QUERY

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "blue")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "red")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS CHILD FILTER

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "blue")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "red")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));
    }

    @Test
    public void simpleChildQueryWithFlushAnd3Shards() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 3)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        // index simple data with flushes, so we have many segments
        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        // TOP CHILDREN QUERY

        SearchResponse searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "yellow"))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "blue"))).execute().actionGet();
        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "red"))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS CHILD QUERY

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "blue")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(hasChildQuery("child", termQuery("c_field", "red")).executionType(getExecutionMethod())).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        // HAS CHILD FILTER

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "blue")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "red")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));
    }

    @Test
    public void testScopedFacet() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        // index simple data
        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").execute().actionGet();
        client.prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").execute().actionGet();
        client.prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").execute().actionGet();
        client.prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch("test")
                .setQuery(topChildrenQuery("child", boolQuery().should(termQuery("c_field", "red")).should(termQuery("c_field", "yellow"))).scope("child1"))
                .addFacet(termsFacet("facet1").field("c_field").scope("child1"))
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), anyOf(equalTo("p2"), equalTo("p1")));
        assertThat(searchResponse.hits().getAt(1).id(), anyOf(equalTo("p2"), equalTo("p1")));

        assertThat(searchResponse.facets().facets().size(), equalTo(1));
        TermsFacet termsFacet = searchResponse.facets().facet("facet1");
        assertThat(termsFacet.entries().size(), equalTo(2));
        assertThat(termsFacet.entries().get(0).term().string(), equalTo("red"));
        assertThat(termsFacet.entries().get(0).count(), equalTo(2));
        assertThat(termsFacet.entries().get(1).term().string(), equalTo("yellow"));
        assertThat(termsFacet.entries().get(1).count(), equalTo(1));
    }

    @Test
    public void testDeletedParent() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        // index simple data
        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").execute().actionGet();
        client.prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").execute().actionGet();
        client.prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").execute().actionGet();
        client.prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        // TOP CHILDREN QUERY

        SearchResponse searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "yellow"))).execute().actionGet();
        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.hits().getAt(0).sourceAsString(), containsString("\"p_value1\""));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.hits().getAt(0).sourceAsString(), containsString("\"p_value1\""));

        // update p1 and see what that we get updated values...

        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1_updated").execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();

        searchResponse = client.prepareSearch("test").setQuery(topChildrenQuery("child", termQuery("c_field", "yellow"))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.hits().getAt(0).sourceAsString(), containsString("\"p_value1_updated\""));

        searchResponse = client.prepareSearch("test").setQuery(constantScoreQuery(hasChildFilter("child", termQuery("c_field", "yellow")).executionType(getExecutionMethod()))).execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p1"));
        assertThat(searchResponse.hits().getAt(0).sourceAsString(), containsString("\"p_value1_updated\""));
    }

    @Test
    public void testDfsSearchType() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 2)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        // index simple data
        client.prepareIndex("test", "parent", "p1").setSource("p_field", "p_value1").execute().actionGet();
        client.prepareIndex("test", "child", "c1").setSource("c_field", "red").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "child", "c2").setSource("c_field", "yellow").setParent("p1").execute().actionGet();
        client.prepareIndex("test", "parent", "p2").setSource("p_field", "p_value2").execute().actionGet();
        client.prepareIndex("test", "child", "c3").setSource("c_field", "blue").setParent("p2").execute().actionGet();
        client.prepareIndex("test", "child", "c4").setSource("c_field", "red").setParent("p2").execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch("test").setSearchType(SearchType.DFS_QUERY_THEN_FETCH)
                .setQuery(boolQuery().mustNot(hasChildQuery("child", boolQuery().should(queryString("c_field:*"))).executionType(getExecutionMethod())))
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
    }

    @Test
    public void testTopChildrenReSearchBug() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();


        int numberOfParents = 4;
        int numberOfChildrenPerParent = 123;
        for (int i = 1; i <= numberOfParents; i++) {
            String parentId = String.format("p%d", i);
            client.prepareIndex("test", "parent", parentId)
                    .setSource("p_field", String.format("p_value%d", i))
                    .execute()
                    .actionGet();
            for (int j = 1; j <= numberOfChildrenPerParent; j++) {
                client.prepareIndex("test", "child", String.format("%s_c%d", parentId, j))
                        .setSource(
                                "c_field1", parentId,
                                "c_field2", i % 2 == 0 ? "even" : "not_even"
                        ).setParent(parentId)
                        .execute()
                        .actionGet();
            }
        }

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch("test")
                .setQuery(topChildrenQuery("child", termQuery("c_field1", "p3")))
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p3"));

        searchResponse = client.prepareSearch("test")
                .setQuery(topChildrenQuery("child", termQuery("c_field2", "even")))
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(2l));
        assertThat(searchResponse.hits().getAt(0).id(), equalTo("p2"));
    }

    @Test
    public void testHasChildAndHasParentFailWhenSomeSegmentsDontContainAnyParentOrChildDocs() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(
                    ImmutableSettings.settingsBuilder()
                            .put("index.number_of_shards", 1)
                            .put("index.number_of_replicas", 0)
                ).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(
                XContentFactory.jsonBuilder()
                        .startObject()
                            .startObject("type")
                                .startObject("_parent")
                                    .field("type", "parent")
                                .endObject()
                .           endObject()
                        .endObject()
        ).execute().actionGet();

        client.prepareIndex("test", "parent", "1").setSource("p_field", 1).execute().actionGet();
        client.prepareIndex("test", "child", "1").setParent("1").setSource("c_field", 1).execute().actionGet();
        client.admin().indices().prepareFlush("test").execute().actionGet();

        client.prepareIndex("test", "type1", "1").setSource("p_field", "p_value1").execute().actionGet();
        client.admin().indices().prepareFlush("test").execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasChildFilter("child", matchAllQuery())))
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));

        client.prepareSearch("test")
                .setQuery(filteredQuery(matchAllQuery(), hasParentFilter("parent", matchAllQuery())))
                .execute().actionGet();
        assertThat("Failures " + Arrays.toString(searchResponse.shardFailures()), searchResponse.shardFailures().length, equalTo(0));
        assertThat(searchResponse.failedShards(), equalTo(0));
        assertThat(searchResponse.hits().totalHits(), equalTo(1l));
    }

    @Test
    public void testCountApiUsage() throws Exception {
        client.admin().indices().prepareDelete().execute().actionGet();

        client.admin().indices().prepareCreate("test").setSettings(ImmutableSettings.settingsBuilder().put("index.number_of_shards", 1)).execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();
        client.admin().indices().preparePutMapping("test").setType("child").setSource(XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_parent").field("type", "parent").endObject()
                .endObject().endObject()).execute().actionGet();

        String parentId = "p1";
        client.prepareIndex("test", "parent", parentId)
                .setSource("p_field", "p_value1")
                .execute().actionGet();
        client.prepareIndex("test", "child", parentId + "_c1")
                .setSource("c_field1", parentId)
                .setParent(parentId)
                .execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();

        CountResponse countResponse = client.prepareCount("test")
                .setQuery(topChildrenQuery("child", termQuery("c_field1", "1")))
                .execute().actionGet();
        assertThat(countResponse.failedShards(), equalTo(1));
        assertThat(countResponse.shardFailures().get(0).reason().contains("top_children query hasn't executed properly"), equalTo(true));

        countResponse = client.prepareCount("test")
                .setQuery(hasChildQuery("child", termQuery("c_field1", "2")).executionType(getExecutionMethod()))
                .execute().actionGet();
        assertThat(countResponse.failedShards(), equalTo(1));
        assertThat(countResponse.shardFailures().get(0).reason().contains("has_child filter/query hasn't executed properly"), equalTo(true));

        countResponse = client.prepareCount("test")
                .setQuery(hasParentQuery("parent", termQuery("p_field1", "1")).executionType(getExecutionMethod()))
                .execute().actionGet();
        assertThat(countResponse.failedShards(), equalTo(1));
        assertThat(countResponse.shardFailures().get(0).reason().contains("has_parent filter/query hasn't executed properly"), equalTo(true));
    }

}
