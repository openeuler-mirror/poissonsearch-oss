/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.test.integration.explain;

import org.elasticsearch.action.explain.ExplainResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.indices.IndexMissingException;
import org.elasticsearch.test.integration.AbstractNodesTests;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 */
public class ExplainActionTests extends AbstractNodesTests {

    protected Client client;

    @BeforeClass
    public void startNodes() {
        startNode("node1");
        startNode("node2");
        client = client("node1");
    }

    @AfterClass
    public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    @Test
    public void testSimple() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (IndexMissingException e) {}
        client.admin().indices().prepareCreate("test").setSettings(
                ImmutableSettings.settingsBuilder().put("index.refresh_interval", -1)
        ).execute().actionGet();
        client.admin().cluster().prepareHealth("test").setWaitForGreenStatus().execute().actionGet();

        client.prepareIndex("test", "test", "1")
                .setSource("field", "value1")
                .execute().actionGet();

        ExplainResponse response = client.prepareExplain("test", "test", "1")
                .setQuery(QueryBuilders.matchAllQuery())
                .execute().actionGet();
        assertNotNull(response);
        assertFalse(response.exists()); // not a match b/c not realtime
        assertFalse(response.match()); // not a match b/c not realtime

        client.admin().indices().prepareRefresh("test").execute().actionGet();
        response = client.prepareExplain("test", "test", "1")
                .setQuery(QueryBuilders.matchAllQuery())
                .execute().actionGet();
        assertNotNull(response);
        assertTrue(response.match());
        assertNotNull(response.explanation());
        assertTrue(response.explanation().isMatch());
        assertThat(response.explanation().getValue(), equalTo(1.0f));

        client.admin().indices().prepareRefresh("test").execute().actionGet();
        response = client.prepareExplain("test", "test", "1")
                .setQuery(QueryBuilders.termQuery("field", "value2"))
                .execute().actionGet();
        assertNotNull(response);
        assertTrue(response.exists());
        assertFalse(response.match());
        assertNotNull(response.explanation());
        assertFalse(response.explanation().isMatch());

        client.admin().indices().prepareRefresh("test").execute().actionGet();
        response = client.prepareExplain("test", "test", "1")
                .setQuery(QueryBuilders.boolQuery()
                        .must(QueryBuilders.termQuery("field", "value1"))
                        .must(QueryBuilders.termQuery("field", "value2"))
                )
                .execute().actionGet();
        assertNotNull(response);
        assertTrue(response.exists());
        assertFalse(response.match());
        assertNotNull(response.explanation());
        assertFalse(response.explanation().isMatch());
        assertThat(response.explanation().getDetails().length, equalTo(2));

        response = client.prepareExplain("test", "test", "2")
                .setQuery(QueryBuilders.matchAllQuery())
                .execute().actionGet();
        assertNotNull(response);
        assertFalse(response.exists());
        assertFalse(response.match());
    }

    @Test
    public void testExplainWithAlias() throws Exception {
        try {
            client.admin().indices().prepareDelete("test").execute().actionGet();
        } catch (IndexMissingException e) {}
        client.admin().indices().prepareCreate("test")
                .execute().actionGet();
        client.admin().cluster().prepareHealth("test").setWaitForGreenStatus().execute().actionGet();

        client.admin().indices().prepareAliases().addAlias("test", "alias1", FilterBuilders.termFilter("field2", "value2"))
                .execute().actionGet();
        client.prepareIndex("test", "test", "1").setSource("field1", "value1", "field2", "value1").execute().actionGet();
        client.admin().indices().prepareRefresh("test").execute().actionGet();

        ExplainResponse response = client.prepareExplain("alias1", "test", "1")
                .setQuery(QueryBuilders.matchAllQuery())
                .execute().actionGet();
        assertNotNull(response);
        assertTrue(response.exists());
        assertFalse(response.match());
    }

}
