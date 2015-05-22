/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transform.search;

import org.elasticsearch.action.indexedscripts.put.PutIndexedScriptRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.client.Requests;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.watcher.actions.ActionStatus;
import org.elasticsearch.watcher.actions.ActionWrapper;
import org.elasticsearch.watcher.actions.ExecutableActions;
import org.elasticsearch.watcher.condition.always.ExecutableAlwaysCondition;
import org.elasticsearch.watcher.execution.TriggeredExecutionContext;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.input.simple.ExecutableSimpleInput;
import org.elasticsearch.watcher.input.simple.SimpleInput;
import org.elasticsearch.watcher.support.WatcherUtils;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.transform.Transform;
import org.elasticsearch.watcher.transform.TransformBuilders;
import org.elasticsearch.watcher.trigger.schedule.IntervalSchedule;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTrigger;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.Watch;
import org.elasticsearch.watcher.watch.WatchStatus;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.FilterBuilders.*;
import static org.elasticsearch.index.query.QueryBuilders.*;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.SUITE;
import static org.elasticsearch.watcher.support.WatcherDateUtils.parseDate;
import static org.elasticsearch.watcher.test.WatcherTestUtils.*;
import static org.hamcrest.Matchers.*;

/**
 *
 */
@ElasticsearchIntegrationTest.ClusterScope(scope = SUITE, numClientNodes = 0, transportClientRatio = 0, randomDynamicTemplates = false, numDataNodes = 1)
public class SearchTransformTests extends ElasticsearchIntegrationTest {

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        //Set path so ScriptService will pick up the test scripts
        return settingsBuilder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("path.conf", this.getResource("config").getPath()).build();
    }

    @Override
    public Settings indexSettings() {
        return settingsBuilder()
                .put(super.indexSettings())

                // we have to test this on an index that has at least 2 shards. Otherwise when searching indices with
                // a single shard the QUERY_THEN_FETCH search type will change to QUERY_AND_FETCH during execution.
                .put("index.number_of_shards", randomIntBetween(2, 5))

                .build();
    }

    @Test
    public void testApply() throws Exception {

        index("idx", "type", "1");
        ensureGreen("idx");
        refresh();

        SearchRequest request = Requests.searchRequest("idx").source(jsonBuilder().startObject()
                .startObject("query")
                .startObject("match_all").endObject()
                .endObject()
                .endObject());
        SearchTransform searchTransform = TransformBuilders.searchTransform(request).build();
        ExecutableSearchTransform transform = new ExecutableSearchTransform(searchTransform, logger, ClientProxy.of(client()));

        WatchExecutionContext ctx = mockExecutionContext("_name", EMPTY_PAYLOAD);

        Transform.Result result = transform.execute(ctx, EMPTY_PAYLOAD);
        assertThat(result, notNullValue());
        assertThat(result.type(), is(SearchTransform.TYPE));

        SearchResponse response = client().search(request).get();
        Payload expectedPayload = new Payload.XContent(response);

        // we need to remove the "took" field from teh response as this is the only field
        // that most likely be different between the two... we don't really care about this
        // field, we just want to make sure that the important parts of the response are the same
        Map<String, Object> resultData = result.payload().data();
        resultData.remove("took");
        Map<String, Object> expectedData = expectedPayload.data();
        expectedData.remove("took");

        assertThat(resultData, equalTo(expectedData));
    }

    @Test
    public void testApply_MustacheTemplate() throws Exception {

        // The rational behind this test:
        //
        // - we index 4 documents each one associated with a unique value and each is associated with a day
        // - we build a search transform such that with a filter that
        //   - the date must be after [scheduled_time] variable
        //   - the date must be before [execution_time] variable
        //   - the value must match [payload.value] variable
        // - the variable are set as such:
        //   - scheduled_time = youngest document's date
        //   - fired_time = oldest document's date
        //   - payload.value = val_3
        // - when executed, the variables will be replaced with the scheduled_time, fired_time and the payload.value.
        // - we set all these variables accordingly (the search transform is responsible to populate them)
        // - when replaced correctly, the search should return document 3.
        //
        // we then do a search for document 3, and compare the response to the payload returned by the transform

        index("idx", "type", "1", doc("2015-01-01T00:00:00", "val_1"));
        index("idx", "type", "2", doc("2015-01-02T00:00:00", "val_2"));
        index("idx", "type", "3", doc("2015-01-03T00:00:00", "val_3"));
        index("idx", "type", "4", doc("2015-01-04T00:00:00", "val_4"));

        ensureGreen("idx");
        refresh();

        SearchRequest request = Requests.searchRequest("idx").source(searchSource().query(filteredQuery(matchAllQuery(), boolFilter()
                .must(rangeFilter("date").gt("{{ctx.trigger.scheduled_time}}"))
                .must(rangeFilter("date").lt("{{ctx.execution_time}}"))
                .must(termFilter("value", "{{ctx.payload.value}}")))));

        SearchTransform searchTransform = TransformBuilders.searchTransform(request).build();
        ExecutableSearchTransform transform = new ExecutableSearchTransform(searchTransform, logger, ClientProxy.of(client()));

        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_name", parseDate("2015-01-04T00:00:00", UTC), parseDate("2015-01-01T00:00:00", UTC));
        WatchExecutionContext ctx = mockExecutionContext("_name", parseDate("2015-01-04T00:00:00", UTC), event, EMPTY_PAYLOAD);

        Payload payload = simplePayload("value", "val_3");

        Transform.Result result = transform.execute(ctx, payload);
        assertThat(result, notNullValue());
        assertThat(result.type(), is(SearchTransform.TYPE));

        SearchResponse response = client().prepareSearch("idx").setQuery(
                filteredQuery(matchAllQuery(), termFilter("value", "val_3")))
                .get();
        Payload expectedPayload = new Payload.XContent(response);

        // we need to remove the "took" field from teh response as this is the only field
        // that most likely be different between the two... we don't really care about this
        // field, we just want to make sure that the important parts of the response are the same
        Map<String, Object> resultData = result.payload().data();
        resultData.remove("took");
        Map<String, Object> expectedData = expectedPayload.data();
        expectedData.remove("took");

        assertThat(resultData, equalTo(expectedData));
    }

    @Test
    public void testParser() throws Exception {
        String[] indices = rarely() ? null : randomBoolean() ? new String[] { "idx" } : new String[] { "idx1", "idx2" };
        SearchType searchType = getRandomSupportedSearchType();
        String templateName = randomBoolean() ? null : "template1";
        XContentBuilder builder = jsonBuilder().startObject();
        if (indices != null) {
            builder.array("indices", indices);
        }
        if (searchType != null) {
            builder.field("search_type", searchType.name());
        }
        if (templateName != null) {
            Template template = Template.file(templateName).build();
            builder.field("template", template);
        }

        XContentBuilder sourceBuilder = jsonBuilder().startObject()
                    .startObject("query")
                        .startObject("match_all")
                        .endObject()
                    .endObject()
                .endObject();
        BytesReference source = sourceBuilder.bytes();

        builder.startObject("body")
                .startObject("query")
                .startObject("match_all")
                .endObject()
                .endObject()
                .endObject();

        builder.endObject();
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();
        ExecutableSearchTransform executable = new SearchTransformFactory(ImmutableSettings.EMPTY, ClientProxy.of(client())).parseExecutable("_id", parser);
        assertThat(executable, notNullValue());
        assertThat(executable.type(), is(SearchTransform.TYPE));
        assertThat(executable.transform().getRequest(), notNullValue());
        if (indices != null) {
            assertThat(executable.transform().getRequest().indices(), arrayContainingInAnyOrder(indices));
        }
        if (searchType != null) {
            assertThat(executable.transform().getRequest().searchType(), is(searchType));
        }
        if (templateName != null) {
            assertThat(executable.transform().getRequest().templateSource().toUtf8(), equalTo("{\"file\":\"template1\"}"));
        }
        assertThat(executable.transform().getRequest().source().toBytes(), equalTo(source.toBytes()));
    }

    @Test(expected = SearchTransformException.class)
    public void testParser_ScanNotSupported() throws Exception {
        SearchRequest request = client().prepareSearch()
                .setSearchType(SearchType.SCAN)
                .request()
                .source(searchSource()
                        .query(filteredQuery(matchQuery("event_type", "a"), rangeFilter("_timestamp").from("{{ctx.trigger.scheduled_time}}||-30s").to("{{ctx.trigger.triggered_time}}"))));

        SearchTransform searchTransform = TransformBuilders.searchTransform(request).build();
        XContentBuilder builder = jsonBuilder().value(searchTransform);
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        SearchTransformFactory factory = new SearchTransformFactory(ImmutableSettings.EMPTY, ClientProxy.of(client()));

        factory.parseTransform("_id", parser);
        fail("expected a SearchTransformException as search type SCAN should not be supported");
    }


    @Test
    public void testSearch_InlineTemplate() throws Exception {
        final String templateQuery = "{\"query\":{\"filtered\":{\"query\":{\"match\":{\"event_type\":{\"query\":\"a\"," +
                "\"type\":\"boolean\"}}},\"filter\":{\"range\":{\"_timestamp\":" +
                "{\"from\":\"{{ctx.trigger.scheduled_time}}||-{{seconds_param}}\",\"to\":\"{{ctx.trigger.scheduled_time}}\"," +
                "\"include_lower\":true,\"include_upper\":true}}}}}}";

        final String expectedQuery = "{\"template\":{\"query\":{\"filtered\":{\"query\":{\"match\":{\"event_type\":{\"query\":\"a\"," +
                "\"type\":\"boolean\"}}},\"filter\":{\"range\":{\"_timestamp\":" +
                "{\"from\":\"{{ctx.trigger.scheduled_time}}||-{{seconds_param}}\",\"to\":\"{{ctx.trigger.scheduled_time}}\"," +
                "\"include_lower\":true,\"include_upper\":true}}}}}},\"params\":{\"seconds_param\":\"30s\",\"ctx\":{\"metadata\":null,\"watch_id\":\"test-watch\",\"payload\":{},\"trigger\":{\"triggered_time\":\"1970-01-01T00:01:00.000Z\",\"scheduled_time\":\"1970-01-01T00:01:00.000Z\"},\"execution_time\":\"1970-01-01T00:01:00.000Z\"}}}";

        Map<String, Object> params = new HashMap<>();
        params.put("seconds_param", "30s");

        BytesReference templateSource = jsonBuilder()
                .value(Template.inline(templateQuery).params(params).build())
                .bytes();
        SearchRequest request = client()
                .prepareSearch()
                .setSearchType(ExecutableSearchTransform.DEFAULT_SEARCH_TYPE)
                .setIndices("test-search-index")
                .setTemplateSource(templateSource)
                .request();

        SearchTransform.Result executedResult = executeSearchTransform(request);

        assertThat(areJsonEquivalent(executedResult.executedRequest().templateSource().toUtf8(), expectedQuery), is(true));
    }

    @Test
    public void testSearch_IndexedTemplate() throws Exception {
        final String templateQuery = "{\"query\":{\"filtered\":{\"query\":{\"match\":{\"event_type\":{\"query\":\"a\"," +
                "\"type\":\"boolean\"}}},\"filter\":{\"range\":{\"_timestamp\":" +
                "{\"from\":\"{{ctx.trigger.scheduled_time}}||-{{seconds_param}}\",\"to\":\"{{ctx.trigger.scheduled_time}}\"," +
                "\"include_lower\":true,\"include_upper\":true}}}}}}";

        PutIndexedScriptRequest indexedScriptRequest = client().preparePutIndexedScript("mustache","test-script", templateQuery).request();
        assertThat(client().putIndexedScript(indexedScriptRequest).actionGet().isCreated(), is(true));

        Map<String, Object> params = new HashMap<>();
        params.put("seconds_param", "30s");

        BytesReference templateSource = jsonBuilder()
                .value(Template.indexed("test-script").params(params).build())
                .bytes();
        SearchRequest request = client()
                .prepareSearch()
                .setSearchType(ExecutableSearchTransform.DEFAULT_SEARCH_TYPE)
                .setIndices("test-search-index")
                .setTemplateSource(templateSource)
                .request();

        SearchTransform.Result result = executeSearchTransform(request);
        assertNotNull(result.executedRequest());
        assertThat(result.executedRequest().templateSource().toUtf8(), startsWith("{\"template\":{\"id\":\"test-script\""));
    }

    @Test
    public void testSearch_OndiskTemplate() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("seconds_param", "30s");

        BytesReference templateSource = jsonBuilder()
                .value(Template.file("test_disk_template").params(params).build())
                .bytes();
        SearchRequest request = client()
                .prepareSearch()
                .setSearchType(ExecutableSearchTransform.DEFAULT_SEARCH_TYPE)
                .setIndices("test-search-index")
                .setTemplateSource(templateSource)
                .request();

        SearchTransform.Result result = executeSearchTransform(request);
        assertNotNull(result.executedRequest());
        assertThat(result.executedRequest().templateSource().toUtf8(), startsWith("{\"template\":{\"file\":\"test_disk_template\""));
    }


    @Test
    public void testDifferentSearchType() throws Exception {
        SearchSourceBuilder searchSourceBuilder = searchSource().query(filteredQuery(
                matchQuery("event_type", "a"),
                rangeFilter("_timestamp")
                        .from("{{ctx.trigger.scheduled_time}}||-30s")
                        .to("{{ctx.trigger.triggered_time}}")));

        final SearchType searchType = getRandomSupportedSearchType();
        SearchRequest request = client()
                .prepareSearch("test-search-index")
                .setSearchType(searchType)
                .request()
                .source(searchSourceBuilder);

        SearchTransform.Result result = executeSearchTransform(request);

        assertThat((Integer) XContentMapValues.extractValue("hits.total", result.payload().data()), equalTo(0));
        assertThat(result.executedRequest(), notNullValue());
        assertThat(result.executedRequest().searchType(), is(searchType));
        assertThat(result.executedRequest().indices(), arrayContainingInAnyOrder(request.indices()));
        assertThat(result.executedRequest().indicesOptions(), equalTo(request.indicesOptions()));
    }

    @Test
    public void testResultParser() throws Exception {
        Map<String, Object> data = new HashMap<>();
        data.put("foo", "bar");
        data.put("baz", new ArrayList<String>() );

        SearchSourceBuilder searchSourceBuilder = searchSource().query(
                filteredQuery(matchQuery("event_type", "a"), rangeFilter("_timestamp").from("{{ctx.triggered.scheduled_time}}||-30s").to("{{ctx.triggered.triggered_time}}")));
        SearchRequest request = client()
                .prepareSearch()
                .setSearchType(ExecutableSearchTransform.DEFAULT_SEARCH_TYPE)
                .request()
                .source(searchSourceBuilder);

        XContentBuilder jsonBuilder = jsonBuilder();
        jsonBuilder.startObject();
        jsonBuilder.field(SearchTransform.Field.PAYLOAD.getPreferredName(), data);
        jsonBuilder.field(SearchTransform.Field.REQUEST.getPreferredName());
        WatcherUtils.writeSearchRequest(request, jsonBuilder, ToXContent.EMPTY_PARAMS);
        jsonBuilder.endObject();

        SearchTransformFactory factory = new SearchTransformFactory(settingsBuilder().build(), ClientProxy.of(client()));

        XContentParser parser = JsonXContent.jsonXContent.createParser(jsonBuilder.bytes());
        parser.nextToken();
        SearchTransform.Result result = factory.parseResult("_id", parser);

        assertEquals(SearchTransform.TYPE, result.type());
        assertEquals(result.payload().data().get("foo"), "bar");
        List baz = (List)result.payload().data().get("baz");
        assertTrue(baz.isEmpty());
        assertNotNull(result.executedRequest());
    }


    private SearchTransform.Result executeSearchTransform(SearchRequest request) throws IOException {
        createIndex("test-search-index");
        ensureGreen("test-search-index");

        SearchTransform searchTransform = TransformBuilders.searchTransform(request).build();
        ExecutableSearchTransform executableSearchTransform = new ExecutableSearchTransform(searchTransform, logger, ClientProxy.of(client()));

        WatchExecutionContext ctx = new TriggeredExecutionContext(
                new Watch("test-watch",
                        new ScheduleTrigger(new IntervalSchedule(new IntervalSchedule.Interval(1, IntervalSchedule.Interval.Unit.MINUTES))),
                        new ExecutableSimpleInput(new SimpleInput(new Payload.Simple()), logger),
                        new ExecutableAlwaysCondition(logger),
                        null,
                        null,
                        new ExecutableActions(new ArrayList<ActionWrapper>()),
                        null,
                        new WatchStatus(ImmutableMap.<String, ActionStatus>of())),
                new DateTime(60000, UTC),
                new ScheduleTriggerEvent("test-watch", new DateTime(60000, UTC), new DateTime(60000, UTC)),
                timeValueSeconds(5));

        return executableSearchTransform.execute(ctx, Payload.Simple.EMPTY);
    }


    private static Map<String, Object> doc(String date, String value) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("date", parseDate(date, UTC));
        doc.put("value", value);
        return doc;
    }

}
