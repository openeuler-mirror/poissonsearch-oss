/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.test.integration.search.facet;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.facet.filter.FilterFacet;
import org.elasticsearch.search.facet.histogram.HistogramFacet;
import org.elasticsearch.search.facet.range.RangeFacet;
import org.elasticsearch.search.facet.statistical.StatisticalFacet;
import org.elasticsearch.search.facet.terms.TermsFacet;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.xcontent.FilterBuilders.*;
import static org.elasticsearch.index.query.xcontent.QueryBuilders.*;
import static org.elasticsearch.search.facet.FacetBuilders.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
public class SimpleFacetsTests extends AbstractNodesTests {

    private Client client;

    @BeforeClass public void createNodes() throws Exception {
        startNode("server1");
        startNode("server2");
        client = getClient();
    }

    @AfterClass public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    protected Client getClient() {
        return client("server1");
    }

    @Test public void testFacetsWithSize0() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .startArray("tag").value("xxx").value("yyy").endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .startArray("tag").value("zzz").value("yyy").endArray()
                .endObject()).execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch()
                .setSize(0)
                .setQuery(termQuery("stag", "111"))
                .addFacet(termsFacet("facet1").field("stag").size(10))
                .execute().actionGet();

        assertThat(searchResponse.hits().hits().length, equalTo(0));

        TermsFacet facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(1));
        assertThat(facet.entries().get(0).term(), equalTo("111"));
        assertThat(facet.entries().get(0).count(), equalTo(2));

        searchResponse = client.prepareSearch()
                .setSearchType(SearchType.QUERY_AND_FETCH)
                .setSize(0)
                .setQuery(termQuery("stag", "111"))
                .addFacet(termsFacet("facet1").field("stag").size(10))
                .addFacet(termsFacet("facet2").field("tag").size(10))
                .execute().actionGet();

        assertThat(searchResponse.hits().hits().length, equalTo(0));

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(1));
        assertThat(facet.entries().get(0).term(), equalTo("111"));
        assertThat(facet.entries().get(0).count(), equalTo(2));
    }

    @Test public void testTermsIndexFacet() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
            client.admin().indices().prepareDelete("test1").execute().actionGet();
            client.admin().indices().prepareDelete("test2").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test1").execute().actionGet();
        client.admin().indices().prepareCreate("test2").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test1", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .endObject()).execute().actionGet();

        client.prepareIndex("test1", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .endObject()).execute().actionGet();

        client.prepareIndex("test2", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .endObject()).execute().actionGet();
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();


        SearchResponse searchResponse = client.prepareSearch()
                .setSize(0)
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("_index").size(10))
                .execute().actionGet();


        TermsFacet facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).term(), equalTo("test1"));
        assertThat(facet.entries().get(0).count(), equalTo(2));
        assertThat(facet.entries().get(1).term(), equalTo("test2"));
        assertThat(facet.entries().get(1).count(), equalTo(1));
    }

    @Test public void testFilterFacets() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .startArray("tag").value("xxx").value("yyy").endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .startArray("tag").value("zzz").value("yyy").endArray()
                .endObject()).execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(filterFacet("facet1").filter(termFilter("stag", "111")))
                .addFacet(filterFacet("facet2").filter(termFilter("tag", "xxx")))
                .addFacet(filterFacet("facet3").filter(termFilter("tag", "yyy")))
                .execute().actionGet();

        FilterFacet facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.count(), equalTo(2l));
    }

    @Test public void testTermsFacets() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .startArray("tag").value("xxx").value("yyy").endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("stag", "111")
                .startArray("tag").value("zzz").value("yyy").endArray()
                .endObject()).execute().actionGet();

        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch()
                .setQuery(termQuery("stag", "111"))
                .addFacet(termsFacet("facet1").field("stag").size(10))
                .addFacet(termsFacet("facet2").field("tag").size(10))
                .execute().actionGet();

        TermsFacet facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(1));
        assertThat(facet.entries().get(0).term(), equalTo("111"));
        assertThat(facet.entries().get(0).count(), equalTo(2));

        facet = searchResponse.facets().facet("facet2");
        assertThat(facet.name(), equalTo("facet2"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).term(), equalTo("yyy"));
        assertThat(facet.entries().get(0).count(), equalTo(2));

        // Test Facet Filter

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("stag").size(10).facetFilter(termFilter("tag", "xxx")))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(1));
        assertThat(facet.entries().get(0).term(), equalTo("111"));
        assertThat(facet.entries().get(0).count(), equalTo(1));

        // Test Facet Filter (with a type)

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("type1.stag").size(10).facetFilter(termFilter("tag", "xxx")))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(1));
        assertThat(facet.entries().get(0).term(), equalTo("111"));
        assertThat(facet.entries().get(0).count(), equalTo(1));

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("tag").size(10))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).term(), equalTo("yyy"));
        assertThat(facet.entries().get(0).count(), equalTo(2));
        assertThat(facet.entries().get(1).term(), anyOf(equalTo("xxx"), equalTo("zzz")));
        assertThat(facet.entries().get(1).count(), equalTo(1));
        assertThat(facet.entries().get(2).term(), anyOf(equalTo("xxx"), equalTo("zzz")));
        assertThat(facet.entries().get(2).count(), equalTo(1));

        // Bounded Size

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("tag").size(2))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).term(), equalTo("yyy"));
        assertThat(facet.entries().get(0).count(), equalTo(2));
        assertThat(facet.entries().get(1).term(), anyOf(equalTo("xxx"), equalTo("zzz")));
        assertThat(facet.entries().get(1).count(), equalTo(1));

        // Test Exclude

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("tag").size(10).exclude("yyy"))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).term(), anyOf(equalTo("xxx"), equalTo("zzz")));
        assertThat(facet.entries().get(0).count(), equalTo(1));
        assertThat(facet.entries().get(1).term(), anyOf(equalTo("xxx"), equalTo("zzz")));
        assertThat(facet.entries().get(1).count(), equalTo(1));

        // Test Order

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("tag").size(10).order(TermsFacet.ComparatorType.TERM))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).term(), equalTo("xxx"));
        assertThat(facet.entries().get(0).count(), equalTo(1));
        assertThat(facet.entries().get(1).term(), equalTo("yyy"));
        assertThat(facet.entries().get(1).count(), equalTo(2));
        assertThat(facet.entries().get(2).term(), equalTo("zzz"));
        assertThat(facet.entries().get(2).count(), equalTo(1));

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("tag").size(10).order(TermsFacet.ComparatorType.REVERSE_TERM))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(2).term(), equalTo("xxx"));
        assertThat(facet.entries().get(2).count(), equalTo(1));
        assertThat(facet.entries().get(1).term(), equalTo("yyy"));
        assertThat(facet.entries().get(1).count(), equalTo(2));
        assertThat(facet.entries().get(0).term(), equalTo("zzz"));
        assertThat(facet.entries().get(0).count(), equalTo(1));

        // Script

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("tag").size(10).script("term + param1").param("param1", "a").order(TermsFacet.ComparatorType.TERM))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).term(), equalTo("xxxa"));
        assertThat(facet.entries().get(0).count(), equalTo(1));
        assertThat(facet.entries().get(1).term(), equalTo("yyya"));
        assertThat(facet.entries().get(1).count(), equalTo(2));
        assertThat(facet.entries().get(2).term(), equalTo("zzza"));
        assertThat(facet.entries().get(2).count(), equalTo(1));

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("tag").size(10).script("term == 'xxx' ? false : true").order(TermsFacet.ComparatorType.TERM))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).term(), equalTo("yyy"));
        assertThat(facet.entries().get(0).count(), equalTo(2));
        assertThat(facet.entries().get(1).term(), equalTo("zzz"));
        assertThat(facet.entries().get(1).count(), equalTo(1));

        // Fields Facets

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").fields("stag", "tag").size(10))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(4));
        assertThat(facet.entries().get(0).term(), anyOf(equalTo("111"), equalTo("yyy")));
        assertThat(facet.entries().get(0).count(), equalTo(2));
        assertThat(facet.entries().get(1).term(), anyOf(equalTo("111"), equalTo("yyy")));
        assertThat(facet.entries().get(1).count(), equalTo(2));
        assertThat(facet.entries().get(2).term(), anyOf(equalTo("zzz"), equalTo("xxx")));
        assertThat(facet.entries().get(2).count(), equalTo(1));
        assertThat(facet.entries().get(3).term(), anyOf(equalTo("zzz"), equalTo("xxx")));
        assertThat(facet.entries().get(3).count(), equalTo(1));

        // Script Field

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").scriptField("_source.stag").size(10))
                .addFacet(termsFacet("facet2").scriptField("_source.tag").size(10))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(1));
        assertThat(facet.entries().get(0).term(), equalTo("111"));
        assertThat(facet.entries().get(0).count(), equalTo(2));

        facet = searchResponse.facets().facet("facet2");
        assertThat(facet.name(), equalTo("facet2"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).term(), equalTo("yyy"));
        assertThat(facet.entries().get(0).count(), equalTo(2));

        // test joining two scripts using the same facet name :)
        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").scriptField("_source.stag").size(10))
                .addFacet(termsFacet("facet1").scriptField("_source.tag").size(10))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(4));
        assertThat(facet.entries().get(0).term(), anyOf(equalTo("111"), equalTo("yyy")));
        assertThat(facet.entries().get(0).count(), equalTo(2));
        assertThat(facet.entries().get(1).term(), anyOf(equalTo("111"), equalTo("yyy")));
        assertThat(facet.entries().get(1).count(), equalTo(2));
        assertThat(facet.entries().get(2).term(), anyOf(equalTo("zzz"), equalTo("xxx")));
        assertThat(facet.entries().get(2).count(), equalTo(1));
        assertThat(facet.entries().get(3).term(), anyOf(equalTo("zzz"), equalTo("xxx")));
        assertThat(facet.entries().get(3).count(), equalTo(1));
    }

    @Test public void testTermFacetWithEqualTermDistribution() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        // at the end of the index, we should have 10 of each `bar`, `foo`, and `baz`
        for (int i = 0; i < 5; i++) {
            client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                    .field("text", "foo bar")
                    .endObject()).execute().actionGet();
        }
        for (int i = 0; i < 5; i++) {
            client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                    .field("text", "bar baz")
                    .endObject()).execute().actionGet();
        }

        for (int i = 0; i < 5; i++) {
            client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                    .field("text", "baz foo")
                    .endObject()).execute().actionGet();
        }
        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("text").size(3))
                .execute().actionGet();

        TermsFacet facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(3));
        for (int i = 0; i < 3; i++) {
            assertThat(facet.entries().get(i).term(), anyOf(equalTo("foo"), equalTo("bar"), equalTo("baz")));
            assertThat(facet.entries().get(i).count(), equalTo(10));
        }

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("text").size(2))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(2));
        for (int i = 0; i < 2; i++) {
            assertThat(facet.entries().get(i).term(), anyOf(equalTo("foo"), equalTo("bar"), equalTo("baz")));
            assertThat(facet.entries().get(i).count(), equalTo(10));
        }

        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(termsFacet("facet1").field("text").size(1))
                .execute().actionGet();

        facet = searchResponse.facets().facet("facet1");
        assertThat(facet.name(), equalTo("facet1"));
        assertThat(facet.entries().size(), equalTo(1));
        for (int i = 0; i < 1; i++) {
            assertThat(facet.entries().get(i).term(), anyOf(equalTo("foo"), equalTo("bar"), equalTo("baz")));
            assertThat(facet.entries().get(i).count(), equalTo(10));
        }
    }

    @Test public void testStatsFacets() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 1)
                .startArray("multi_num").value(1.0).value(2.0f).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 2)
                .startArray("multi_num").value(3.0).value(4.0f).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(statisticalFacet("stats1").field("num"))
                .addFacet(statisticalFacet("stats2").field("multi_num"))
                .addFacet(statisticalScriptFacet("stats3").script("doc['num'].value * 2"))
                .execute().actionGet();

        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));

        StatisticalFacet facet = searchResponse.facets().facet("stats1");
        assertThat(facet.name(), equalTo(facet.name()));
        assertThat(facet.count(), equalTo(2l));
        assertThat(facet.total(), equalTo(3d));
        assertThat(facet.min(), equalTo(1d));
        assertThat(facet.max(), equalTo(2d));
        assertThat(facet.mean(), equalTo(1.5d));
        assertThat(facet.sumOfSquares(), equalTo(5d));

        facet = searchResponse.facets().facet("stats2");
        assertThat(facet.name(), equalTo(facet.name()));
        assertThat(facet.count(), equalTo(4l));
        assertThat(facet.total(), equalTo(10d));
        assertThat(facet.min(), equalTo(1d));
        assertThat(facet.max(), equalTo(4d));
        assertThat(facet.mean(), equalTo(2.5d));

        facet = searchResponse.facets().facet("stats3");
        assertThat(facet.name(), equalTo(facet.name()));
        assertThat(facet.count(), equalTo(2l));
        assertThat(facet.total(), equalTo(6d));
        assertThat(facet.min(), equalTo(2d));
        assertThat(facet.max(), equalTo(4d));
        assertThat(facet.mean(), equalTo(3d));
        assertThat(facet.sumOfSquares(), equalTo(20d));

        // test multi field facet
        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(statisticalFacet("stats").fields("num", "multi_num"))
                .execute().actionGet();


        facet = searchResponse.facets().facet("stats");
        assertThat(facet.name(), equalTo(facet.name()));
        assertThat(facet.count(), equalTo(6l));
        assertThat(facet.total(), equalTo(13d));
        assertThat(facet.min(), equalTo(1d));
        assertThat(facet.max(), equalTo(4d));
        assertThat(facet.mean(), equalTo(13d / 6d));
        assertThat(facet.sumOfSquares(), equalTo(35d));

        // test cross field facet using the same facet name...
        searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(statisticalFacet("stats").field("num"))
                .addFacet(statisticalFacet("stats").field("multi_num"))
                .execute().actionGet();


        facet = searchResponse.facets().facet("stats");
        assertThat(facet.name(), equalTo(facet.name()));
        assertThat(facet.count(), equalTo(6l));
        assertThat(facet.total(), equalTo(13d));
        assertThat(facet.min(), equalTo(1d));
        assertThat(facet.max(), equalTo(4d));
        assertThat(facet.mean(), equalTo(13d / 6d));
        assertThat(facet.sumOfSquares(), equalTo(35d));
    }

    @Test public void testHistoFacets() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 1055)
                .field("date", "1970-01-01T00:00:00")
                .startArray("multi_num").value(13.0f).value(23.f).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 1065)
                .field("date", "1970-01-01T00:00:25")
                .startArray("multi_num").value(15.0f).value(31.0f).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 1175)
                .field("date", "1970-01-01T00:02:00")
                .startArray("multi_num").value(17.0f).value(25.0f).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(histogramFacet("stats1").field("num").interval(100))
                .addFacet(histogramFacet("stats2").field("multi_num").interval(10))
                .addFacet(histogramFacet("stats3").keyField("num").valueField("multi_num").interval(100))
                .addFacet(histogramScriptFacet("stats4").keyScript("doc['date'].date.minuteOfHour").valueScript("doc['num'].value"))
                .addFacet(histogramFacet("stats5").field("date").interval(1, TimeUnit.MINUTES))
                .addFacet(histogramScriptFacet("stats6").keyField("num").valueScript("doc['num'].value").interval(100))
                .execute().actionGet();

        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));

        HistogramFacet facet = searchResponse.facets().facet("stats1");
        assertThat(facet.name(), equalTo("stats1"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).key(), equalTo(1000l));
        assertThat(facet.entries().get(0).count(), equalTo(2l));
        assertThat(facet.entries().get(0).total(), equalTo(2120d));
        assertThat(facet.entries().get(0).mean(), equalTo(1060d));
        assertThat(facet.entries().get(1).key(), equalTo(1100l));
        assertThat(facet.entries().get(1).count(), equalTo(1l));
        assertThat(facet.entries().get(1).total(), equalTo(1175d));
        assertThat(facet.entries().get(1).mean(), equalTo(1175d));

        facet = searchResponse.facets().facet("stats2");
        assertThat(facet.name(), equalTo("stats2"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).key(), equalTo(10l));
        assertThat(facet.entries().get(0).count(), equalTo(3l));
        assertThat(facet.entries().get(0).total(), equalTo(45d));
        assertThat(facet.entries().get(0).mean(), equalTo(15d));
        assertThat(facet.entries().get(1).key(), equalTo(20l));
        assertThat(facet.entries().get(1).count(), equalTo(2l));
        assertThat(facet.entries().get(1).total(), equalTo(48d));
        assertThat(facet.entries().get(1).mean(), equalTo(24d));
        assertThat(facet.entries().get(2).key(), equalTo(30l));
        assertThat(facet.entries().get(2).count(), equalTo(1l));
        assertThat(facet.entries().get(2).total(), equalTo(31d));
        assertThat(facet.entries().get(2).mean(), equalTo(31d));

        facet = searchResponse.facets().facet("stats3");
        assertThat(facet.name(), equalTo("stats3"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).key(), equalTo(1000l));
        assertThat(facet.entries().get(0).count(), equalTo(4l));
        assertThat(facet.entries().get(0).total(), equalTo(82d));
        assertThat(facet.entries().get(0).mean(), equalTo(20.5d));
        assertThat(facet.entries().get(1).key(), equalTo(1100l));
        assertThat(facet.entries().get(1).count(), equalTo(2l));
        assertThat(facet.entries().get(1).total(), equalTo(42d));
        assertThat(facet.entries().get(1).mean(), equalTo(21d));

        facet = searchResponse.facets().facet("stats4");
        assertThat(facet.name(), equalTo("stats4"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).key(), equalTo(0l));
        assertThat(facet.entries().get(0).count(), equalTo(2l));
        assertThat(facet.entries().get(0).total(), equalTo(2120d));
        assertThat(facet.entries().get(0).mean(), equalTo(1060d));
        assertThat(facet.entries().get(1).key(), equalTo(2l));
        assertThat(facet.entries().get(1).count(), equalTo(1l));
        assertThat(facet.entries().get(1).total(), equalTo(1175d));
        assertThat(facet.entries().get(1).mean(), equalTo(1175d));

        facet = searchResponse.facets().facet("stats5");
        assertThat(facet.name(), equalTo("stats5"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).key(), equalTo(0l));
        assertThat(facet.entries().get(0).count(), equalTo(2l));
        assertThat(facet.entries().get(1).key(), equalTo(TimeValue.timeValueMinutes(2).millis()));
        assertThat(facet.entries().get(1).count(), equalTo(1l));

        facet = searchResponse.facets().facet("stats6");
        assertThat(facet.name(), equalTo("stats6"));
        assertThat(facet.entries().size(), equalTo(2));
        assertThat(facet.entries().get(0).key(), equalTo(1000l));
        assertThat(facet.entries().get(0).count(), equalTo(2l));
        assertThat(facet.entries().get(0).total(), equalTo(2120d));
        assertThat(facet.entries().get(0).mean(), equalTo(1060d));
        assertThat(facet.entries().get(1).key(), equalTo(1100l));
        assertThat(facet.entries().get(1).count(), equalTo(1l));
        assertThat(facet.entries().get(1).total(), equalTo(1175d));
        assertThat(facet.entries().get(1).mean(), equalTo(1175d));
    }

    @Test public void testRangeFacets() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (Exception e) {
            // ignore
        }
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.admin().cluster().prepareHealth().setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 1055)
                .field("value", 1)
                .field("date", "1970-01-01T00:00:00")
                .startArray("multi_num").value(13.0f).value(23.f).endArray()
                .startArray("multi_value").value(10).value(11).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareFlush().setRefresh(true).execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 1065)
                .field("value", 2)
                .field("date", "1970-01-01T00:00:25")
                .startArray("multi_num").value(15.0f).value(31.0f).endArray()
                .startArray("multi_value").value(20).value(21).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();

        client.prepareIndex("test", "type1").setSource(jsonBuilder().startObject()
                .field("num", 1175)
                .field("value", 3)
                .field("date", "1970-01-01T00:00:52")
                .startArray("multi_num").value(17.0f).value(25.0f).endArray()
                .startArray("multi_value").value(30).value(31).endArray()
                .endObject()).execute().actionGet();
        client.admin().indices().prepareRefresh().execute().actionGet();

        SearchResponse searchResponse = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addFacet(rangeFacet("range1").field("num").addUnboundedFrom(1056).addRange(1000, 1170).addUnboundedTo(1170))
                .addFacet(rangeFacet("range2").keyField("num").valueField("value").addUnboundedFrom(1056).addRange(1000, 1170).addUnboundedTo(1170))
                .addFacet(rangeFacet("range3").keyField("num").valueField("multi_value").addUnboundedFrom(1056).addRange(1000, 1170).addUnboundedTo(1170))
                .addFacet(rangeFacet("range4").keyField("multi_num").valueField("value").addUnboundedFrom(16).addRange(10, 26).addUnboundedTo(20))
                .addFacet(rangeScriptFacet("range5").keyScript("doc['num'].value").valueScript("doc['value'].value").addUnboundedFrom(1056).addRange(1000, 1170).addUnboundedTo(1170))
                .addFacet(rangeFacet("range6").field("date").addUnboundedFrom("1970-01-01T00:00:26").addRange("1970-01-01T00:00:15", "1970-01-01T00:00:53").addUnboundedTo("1970-01-01T00:00:26"))
                .execute().actionGet();

        if (searchResponse.failedShards() > 0) {
            logger.warn("Failed shards:");
            for (ShardSearchFailure shardSearchFailure : searchResponse.shardFailures()) {
                logger.warn("-> {}", shardSearchFailure);
            }
        }
        assertThat(searchResponse.failedShards(), equalTo(0));

        RangeFacet facet = searchResponse.facets().facet("range1");
        assertThat(facet.name(), equalTo("range1"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).to(), closeTo(1056, 0.000001));
        assertThat(Double.parseDouble(facet.entries().get(0).toAsString()), closeTo(1056, 0.000001));
        assertThat(facet.entries().get(0).count(), equalTo(1l));
        assertThat(facet.entries().get(0).total(), closeTo(1055, 0.000001));
        assertThat(facet.entries().get(1).from(), closeTo(1000, 0.000001));
        assertThat(Double.parseDouble(facet.entries().get(1).fromAsString()), closeTo(1000, 0.000001));
        assertThat(facet.entries().get(1).to(), closeTo(1170, 0.000001));
        assertThat(Double.parseDouble(facet.entries().get(1).toAsString()), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(1).count(), equalTo(2l));
        assertThat(facet.entries().get(1).total(), closeTo(1055 + 1065, 0.000001));
        assertThat(facet.entries().get(2).from(), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(2).count(), equalTo(1l));
        assertThat(facet.entries().get(2).total(), closeTo(1175, 0.000001));

        facet = searchResponse.facets().facet("range2");
        assertThat(facet.name(), equalTo("range2"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).to(), closeTo(1056, 0.000001));
        assertThat(facet.entries().get(0).count(), equalTo(1l));
        assertThat(facet.entries().get(0).total(), closeTo(1, 0.000001));
        assertThat(facet.entries().get(1).from(), closeTo(1000, 0.000001));
        assertThat(facet.entries().get(1).to(), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(1).count(), equalTo(2l));
        assertThat(facet.entries().get(1).total(), closeTo(3, 0.000001));
        assertThat(facet.entries().get(2).from(), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(2).count(), equalTo(1l));
        assertThat(facet.entries().get(2).total(), closeTo(3, 0.000001));

        facet = searchResponse.facets().facet("range3");
        assertThat(facet.name(), equalTo("range3"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).to(), closeTo(1056, 0.000001));
        assertThat(facet.entries().get(0).count(), equalTo(1l));
        assertThat(facet.entries().get(0).total(), closeTo(10 + 11, 0.000001));
        assertThat(facet.entries().get(1).from(), closeTo(1000, 0.000001));
        assertThat(facet.entries().get(1).to(), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(1).count(), equalTo(2l));
        assertThat(facet.entries().get(1).total(), closeTo(62, 0.000001));
        assertThat(facet.entries().get(2).from(), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(2).count(), equalTo(1l));
        assertThat(facet.entries().get(2).total(), closeTo(61, 0.000001));

        facet = searchResponse.facets().facet("range4");
        assertThat(facet.name(), equalTo("range4"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).to(), closeTo(16, 0.000001));
        assertThat(facet.entries().get(0).count(), equalTo(2l));
        assertThat(facet.entries().get(0).total(), closeTo(3, 0.000001));
        assertThat(facet.entries().get(1).from(), closeTo(10, 0.000001));
        assertThat(facet.entries().get(1).to(), closeTo(26, 0.000001));
        assertThat(facet.entries().get(1).count(), equalTo(5l));
        assertThat(facet.entries().get(1).total(), closeTo(1 * 2 + 2 + 3 * 2, 0.000001));
        assertThat(facet.entries().get(2).from(), closeTo(20, 0.000001));
        assertThat(facet.entries().get(2).count(), equalTo(3l));
        assertThat(facet.entries().get(2).total(), closeTo(1 + 2 + 3, 0.000001));

        facet = searchResponse.facets().facet("range5");
        assertThat(facet.name(), equalTo("range5"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).to(), closeTo(1056, 0.000001));
        assertThat(facet.entries().get(0).count(), equalTo(1l));
        assertThat(facet.entries().get(0).total(), closeTo(1, 0.000001));
        assertThat(facet.entries().get(1).from(), closeTo(1000, 0.000001));
        assertThat(facet.entries().get(1).to(), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(1).count(), equalTo(2l));
        assertThat(facet.entries().get(1).total(), closeTo(3, 0.000001));
        assertThat(facet.entries().get(2).from(), closeTo(1170, 0.000001));
        assertThat(facet.entries().get(2).count(), equalTo(1l));
        assertThat(facet.entries().get(2).total(), closeTo(3, 0.000001));

        facet = searchResponse.facets().facet("range6");
        assertThat(facet.name(), equalTo("range6"));
        assertThat(facet.entries().size(), equalTo(3));
        assertThat(facet.entries().get(0).count(), equalTo(2l));
        assertThat(facet.entries().get(0).toAsString(), equalTo("1970-01-01T00:00:26"));
        assertThat(facet.entries().get(1).count(), equalTo(2l));
        assertThat(facet.entries().get(1).fromAsString(), equalTo("1970-01-01T00:00:15"));
        assertThat(facet.entries().get(1).toAsString(), equalTo("1970-01-01T00:00:53"));
        assertThat(facet.entries().get(2).count(), equalTo(1l));
        assertThat(facet.entries().get(2).fromAsString(), equalTo("1970-01-01T00:00:26"));
    }
}
