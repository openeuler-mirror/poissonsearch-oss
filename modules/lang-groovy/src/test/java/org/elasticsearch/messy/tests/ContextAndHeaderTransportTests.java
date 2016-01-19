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

package org.elasticsearch.messy.tests;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptRequest;
import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptResponse;
import org.elasticsearch.action.percolate.PercolateResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.FilterClient;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.HttpServerTransport;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.GeoShapeQueryBuilder;
import org.elasticsearch.index.query.MoreLikeThisQueryBuilder;
import org.elasticsearch.index.query.MoreLikeThisQueryBuilder.Item;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.query.TermsQueryBuilder;
import org.elasticsearch.index.query.functionscore.script.ScriptScoreFunctionBuilder;
import org.elasticsearch.indices.cache.query.terms.TermsLookup;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService.ScriptType;
import org.elasticsearch.script.groovy.GroovyPlugin;
import org.elasticsearch.script.groovy.GroovyScriptEngineService;
import org.elasticsearch.test.ActionRecordingPlugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.rest.client.http.HttpRequestBuilder;
import org.elasticsearch.test.rest.client.http.HttpResponse;
import org.junit.After;
import org.junit.Before;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_SHARDS;
import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.node.Node.HTTP_ENABLED;
import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.test.ESIntegTestCase.Scope.SUITE;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.hasStatus;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@ClusterScope(scope = SUITE)
public class ContextAndHeaderTransportTests extends ESIntegTestCase {
    private String randomHeaderKey = randomAsciiOfLength(10);
    private String randomHeaderValue = randomAsciiOfLength(20);
    private String queryIndex = "query-" + randomAsciiOfLength(10).toLowerCase(Locale.ROOT);
    private String lookupIndex = "lookup-" + randomAsciiOfLength(10).toLowerCase(Locale.ROOT);

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return settingsBuilder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("script.indexed", "on")
                .put(HTTP_ENABLED, true)
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(ActionRecordingPlugin.class, GroovyPlugin.class);
    }

    @Before
    public void createIndices() throws Exception {
        String mapping = jsonBuilder().startObject().startObject("type")
                .startObject("properties")
                .startObject("location").field("type", "geo_shape").endObject()
                .startObject("name").field("type", "string").endObject()
                .endObject()
                .endObject().endObject().string();

        Settings settings = settingsBuilder()
                .put(indexSettings())
                .put(SETTING_NUMBER_OF_SHARDS, 1) // A single shard will help to keep the tests repeatable.
                .build();
        assertAcked(transportClient().admin().indices().prepareCreate(lookupIndex)
                .setSettings(settings).addMapping("type", mapping));
        assertAcked(transportClient().admin().indices().prepareCreate(queryIndex)
                .setSettings(settings).addMapping("type", mapping));
        ensureGreen(queryIndex, lookupIndex);

        ActionRecordingPlugin.clear();
    }

    @After
    public void checkAllRequestsContainHeaders() {
        assertRequestsContainHeader(IndexRequest.class);
        assertRequestsContainHeader(RefreshRequest.class);
    }

    public void testThatTermsLookupGetRequestContainsContextAndHeaders() throws Exception {
        transportClient().prepareIndex(lookupIndex, "type", "1")
                .setSource(jsonBuilder().startObject().array("followers", "foo", "bar", "baz").endObject()).get();
        transportClient().prepareIndex(queryIndex, "type", "1")
                .setSource(jsonBuilder().startObject().field("username", "foo").endObject()).get();
        transportClient().admin().indices().prepareRefresh(queryIndex, lookupIndex).get();

        TermsQueryBuilder termsLookupFilterBuilder = QueryBuilders.termsLookupQuery("username", new TermsLookup(lookupIndex, "type", "1", "followers"));
        BoolQueryBuilder queryBuilder = QueryBuilders.boolQuery().must(QueryBuilders.matchAllQuery()).must(termsLookupFilterBuilder);

        SearchResponse searchResponse = transportClient()
                .prepareSearch(queryIndex)
                .setQuery(queryBuilder)
                .get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 1);

        assertGetRequestsContainHeaders();
    }

    public void testThatGeoShapeQueryGetRequestContainsContextAndHeaders() throws Exception {
        transportClient().prepareIndex(lookupIndex, "type", "1").setSource(jsonBuilder().startObject()
                .field("name", "Munich Suburban Area")
                .startObject("location")
                .field("type", "polygon")
                .startArray("coordinates").startArray()
                .startArray().value(11.34).value(48.25).endArray()
                .startArray().value(11.68).value(48.25).endArray()
                .startArray().value(11.65).value(48.06).endArray()
                .startArray().value(11.37).value(48.13).endArray()
                .startArray().value(11.34).value(48.25).endArray() // close the polygon
                .endArray().endArray()
                .endObject()
                .endObject())
                .get();
        // second document
        transportClient().prepareIndex(queryIndex, "type", "1").setSource(jsonBuilder().startObject()
                .field("name", "Munich Center")
                .startObject("location")
                .field("type", "point")
                .startArray("coordinates").value(11.57).value(48.13).endArray()
                .endObject()
                .endObject())
                .get();
        transportClient().admin().indices().prepareRefresh(lookupIndex, queryIndex).get();

        GeoShapeQueryBuilder queryBuilder = QueryBuilders.geoShapeQuery("location", "1", "type")
                .indexedShapeIndex(lookupIndex)
                .indexedShapePath("location");

        SearchResponse searchResponse = transportClient()
                .prepareSearch(queryIndex)
                .setQuery(queryBuilder)
                .get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 1);
        assertThat(ActionRecordingPlugin.allRequests(), hasSize(greaterThan(0)));

        assertGetRequestsContainHeaders();
    }

    public void testThatMoreLikeThisQueryMultiTermVectorRequestContainsContextAndHeaders() throws Exception {
        transportClient().prepareIndex(lookupIndex, "type", "1")
                .setSource(jsonBuilder().startObject().field("name", "Star Wars - The new republic").endObject())
                .get();
        transportClient().prepareIndex(queryIndex, "type", "1")
                .setSource(jsonBuilder().startObject().field("name", "Jar Jar Binks - A horrible mistake").endObject())
                .get();
        transportClient().prepareIndex(queryIndex, "type", "2")
                .setSource(jsonBuilder().startObject().field("name", "Star Wars - Return of the jedi").endObject())
                .get();
        transportClient().admin().indices().prepareRefresh(lookupIndex, queryIndex).get();

        MoreLikeThisQueryBuilder moreLikeThisQueryBuilder = QueryBuilders.moreLikeThisQuery(new String[] {"name"}, null,
                new Item[] {new Item(lookupIndex, "type", "1")})
                .minTermFreq(1)
                .minDocFreq(1);

        SearchResponse searchResponse = transportClient()
                .prepareSearch(queryIndex)
                .setQuery(moreLikeThisQueryBuilder)
                .get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 1);

        assertRequestsContainHeader(MultiTermVectorsRequest.class);
    }

    public void testThatPercolatingExistingDocumentGetRequestContainsContextAndHeaders() throws Exception {
        transportClient().prepareIndex(lookupIndex, ".percolator", "1")
                .setSource(jsonBuilder().startObject().startObject("query").startObject("match").field("name", "star wars").endObject().endObject().endObject())
                .get();
        transportClient().prepareIndex(lookupIndex, "type", "1")
                .setSource(jsonBuilder().startObject().field("name", "Star Wars - The new republic").endObject())
                .get();
        transportClient().admin().indices().prepareRefresh(lookupIndex).get();

        GetRequest getRequest = transportClient().prepareGet(lookupIndex, "type", "1").request();
        PercolateResponse response = transportClient().preparePercolate().setDocumentType("type").setGetRequest(getRequest).get();
        assertThat(response.getCount(), is(1l));

        assertGetRequestsContainHeaders();
    }

    public void testThatIndexedScriptGetRequestContainsContextAndHeaders() throws Exception {
        PutIndexedScriptResponse scriptResponse = transportClient().preparePutIndexedScript(GroovyScriptEngineService.NAME, "my_script",
                jsonBuilder().startObject().field("script", "_score * 10").endObject().string()
        ).get();
        assertThat(scriptResponse.isCreated(), is(true));

        transportClient().prepareIndex(queryIndex, "type", "1")
                .setSource(jsonBuilder().startObject().field("name", "Star Wars - The new republic").endObject())
                .get();
        transportClient().admin().indices().prepareRefresh(queryIndex).get();

        SearchResponse searchResponse = transportClient()
                .prepareSearch(queryIndex)
                .setQuery(
                        QueryBuilders.functionScoreQuery(
                                new ScriptScoreFunctionBuilder(new Script("my_script", ScriptType.INDEXED, "groovy", null))).boostMode(
                                CombineFunction.REPLACE)).get();
        assertNoFailures(searchResponse);
        assertHitCount(searchResponse, 1);
        assertThat(searchResponse.getHits().getMaxScore(), is(10.0f));

        assertGetRequestsContainHeaders(".scripts");
        assertRequestsContainHeader(PutIndexedScriptRequest.class);
    }

    public void testThatRelevantHttpHeadersBecomeRequestHeaders() throws Exception {
        String releventHeaderName = "relevant_" + randomHeaderKey;
        for (RestController restController : internalCluster().getDataNodeInstances(RestController.class)) {
            restController.registerRelevantHeaders(releventHeaderName);
        }

        CloseableHttpClient httpClient = HttpClients.createDefault();
        HttpResponse response = new HttpRequestBuilder(httpClient)
                .httpTransport(internalCluster().getDataNodeInstance(HttpServerTransport.class))
                .addHeader(randomHeaderKey, randomHeaderValue)
                .addHeader(releventHeaderName, randomHeaderValue)
                .path("/" + queryIndex + "/_search")
                .execute();

        assertThat(response, hasStatus(OK));
        List<SearchRequest> searchRequests = ActionRecordingPlugin.requestsOfType(SearchRequest.class);
        assertThat(searchRequests, hasSize(greaterThan(0)));
        for (SearchRequest searchRequest : searchRequests) {
            assertThat(searchRequest.hasHeader(releventHeaderName), is(true));
            // was not specified, thus is not included
            assertThat(searchRequest.hasHeader(randomHeaderKey), is(false));
        }
    }

    private void assertRequestsContainHeader(Class<? extends ActionRequest<?>> clazz) {
        List<? extends ActionRequest<?>> classRequests = ActionRecordingPlugin.requestsOfType(clazz);
        for (ActionRequest<?> request : classRequests) {
            assertRequestContainsHeader(request);
        }
    }

    private void assertGetRequestsContainHeaders() {
        assertGetRequestsContainHeaders(this.lookupIndex);
    }

    private void assertGetRequestsContainHeaders(String index) {
        List<GetRequest> getRequests = ActionRecordingPlugin.requestsOfType(GetRequest.class);
        assertThat(getRequests, hasSize(greaterThan(0)));

        for (GetRequest request : getRequests) {
            if (!request.index().equals(index)) {
                continue;
            }
            assertRequestContainsHeader(request);
        }
    }

    private void assertRequestContainsHeader(ActionRequest<?> request) {
        String msg = String.format(Locale.ROOT, "Expected header %s to be in request %s", randomHeaderKey, request.getClass().getName());
        if (request instanceof IndexRequest) {
            IndexRequest indexRequest = (IndexRequest) request;
            msg = String.format(Locale.ROOT, "Expected header %s to be in index request %s/%s/%s", randomHeaderKey,
                    indexRequest.index(), indexRequest.type(), indexRequest.id());
        }
        assertThat(msg, request.hasHeader(randomHeaderKey), is(true));
        assertThat(request.getHeader(randomHeaderKey).toString(), is(randomHeaderValue));
    }

    /**
     * a transport client that adds our random header
     */
    private Client transportClient() {
        Client transportClient = internalCluster().transportClient();
        FilterClient filterClient = new FilterClient(transportClient) {
            @Override
            protected <Request extends ActionRequest<Request>, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>> void doExecute(
                    Action<Request, Response, RequestBuilder> action, Request request,
                    ActionListener<Response> listener) {
                request.putHeader(randomHeaderKey, randomHeaderValue);
                super.doExecute(action, request, listener);
            }
        };

        return filterClient;
    }
}
