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

package org.elasticsearch.test.integration.search.scriptfield;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Map;

import static org.elasticsearch.client.Requests.*;
import static org.elasticsearch.common.xcontent.XContentFactory.*;
import static org.elasticsearch.index.query.xcontent.QueryBuilders.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
@Test
public class ScriptFieldSearchTests extends AbstractNodesTests {

    private Client client;

    @BeforeMethod public void createNodes() throws Exception {
        startNode("server1");
        client = getClient();
    }

    @AfterMethod public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    protected Client getClient() {
        return client("server1");
    }

    @Test
    public void testCustomScriptBoost() throws Exception {
        client.admin().indices().prepareCreate("test").execute().actionGet();
        client.prepareIndex("test", "type1", "1")
                .setSource(jsonBuilder().startObject().field("test", "value beck").field("num1", 1.0f).field("date", "1970-01-01T00:00:00").endObject())
                .execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "type1", "2")
                .setSource(jsonBuilder().startObject().field("test", "value beck").field("num1", 2.0f).field("date", "1970-01-01T00:00:25").endObject())
                .execute().actionGet();
        client.admin().indices().prepareFlush().execute().actionGet();
        client.prepareIndex("test", "type1", "3")
                .setSource(jsonBuilder().startObject().field("test", "value beck").field("num1", 3.0f).field("date", "1970-01-01T00:02:00").endObject())
                .execute().actionGet();
        client.admin().indices().refresh(refreshRequest()).actionGet();

        logger.info("running doc['num1'].value");
        SearchResponse response = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addSort("num1", SortOrder.ASC)
                .addScriptField("sNum1", "doc['num1'].value")
                .addScriptField("date1", "doc['date'].date.millis")
                .execute().actionGet();

        assertThat(response.hits().totalHits(), equalTo(3l));
        assertThat(response.hits().getAt(0).isSourceEmpty(), equalTo(true));
        assertThat(response.hits().getAt(0).id(), equalTo("1"));
        assertThat((Double) response.hits().getAt(0).fields().get("sNum1").values().get(0), equalTo(1.0));
        assertThat((Long) response.hits().getAt(0).fields().get("date1").values().get(0), equalTo(0l));
        assertThat(response.hits().getAt(1).id(), equalTo("2"));
        assertThat((Double) response.hits().getAt(1).fields().get("sNum1").values().get(0), equalTo(2.0));
        assertThat((Long) response.hits().getAt(1).fields().get("date1").values().get(0), equalTo(25000l));
        assertThat(response.hits().getAt(2).id(), equalTo("3"));
        assertThat((Double) response.hits().getAt(2).fields().get("sNum1").values().get(0), equalTo(3.0));
        assertThat((Long) response.hits().getAt(2).fields().get("date1").values().get(0), equalTo(120000l));

        logger.info("running doc['num1'].value * factor");
        Map<String, Object> params = MapBuilder.<String, Object>newMapBuilder().put("factor", 2.0).map();
        response = client.prepareSearch()
                .setQuery(matchAllQuery())
                .addSort("num1", SortOrder.ASC)
                .addScriptField("sNum1", "doc['num1'].value * factor", params)
                .execute().actionGet();

        assertThat(response.hits().totalHits(), equalTo(3l));
        assertThat(response.hits().getAt(0).id(), equalTo("1"));
        assertThat((Double) response.hits().getAt(0).fields().get("sNum1").values().get(0), equalTo(2.0));
        assertThat(response.hits().getAt(1).id(), equalTo("2"));
        assertThat((Double) response.hits().getAt(1).fields().get("sNum1").values().get(0), equalTo(4.0));
        assertThat(response.hits().getAt(2).id(), equalTo("3"));
        assertThat((Double) response.hits().getAt(2).fields().get("sNum1").values().get(0), equalTo(6.0));
    }
}