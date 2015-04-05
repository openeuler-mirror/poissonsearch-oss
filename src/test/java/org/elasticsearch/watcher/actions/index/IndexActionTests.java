/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.index;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.watcher.actions.ActionException;
import org.elasticsearch.watcher.actions.email.service.Authentication;
import org.elasticsearch.watcher.actions.email.service.Email;
import org.elasticsearch.watcher.actions.email.service.EmailService;
import org.elasticsearch.watcher.actions.email.service.Profile;
import org.elasticsearch.watcher.support.http.HttpClient;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.test.WatcherTestUtils;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.Watch;
import org.elasticsearch.watcher.watch.WatchExecutionContext;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 */
public class IndexActionTests extends ElasticsearchIntegrationTest {

    @Test
    public void testIndexActionExecute() throws Exception {

        IndexAction action = new IndexAction(logger, ClientProxy.of(client()), "test-index", "test-type");
        final String account = "account1";
        Watch alert = WatcherTestUtils.createTestWatch("testAlert",
                ClientProxy.of(client()),
                ScriptServiceProxy.of(internalCluster().getInstance(ScriptService.class)),
                new HttpClient(ImmutableSettings.EMPTY),
                new EmailService() {
                    @Override
                    public EmailService.EmailSent send(Email email, Authentication auth, Profile profile) {
                        return new EmailSent(account, email);
                    }

                    @Override
                    public EmailSent send(Email email, Authentication auth, Profile profile, String accountName) {
                        return new EmailSent(account, email);
                    }
                },
                logger);
        WatchExecutionContext ctx = new WatchExecutionContext("testid", alert, new DateTime(), new ScheduleTriggerEvent(new DateTime(), new DateTime()));

        Map<String, Object> payloadMap = new HashMap<>();
        payloadMap.put("test", "foo");
        IndexAction.Result result = action.execute("_id", ctx, new Payload.Simple(payloadMap));

        assertThat(result.success(), equalTo(true));
        Map<String, Object> responseData = result.response().data();
        assertThat(responseData.get("created"), equalTo((Object)Boolean.TRUE));
        assertThat(responseData.get("version"), equalTo((Object) 1L));
        assertThat(responseData.get("type").toString(), equalTo("test-type"));
        assertThat(responseData.get("index").toString(), equalTo("test-index"));

        refresh(); //Manually refresh to make sure data is available

        SearchResponse sr = client().prepareSearch("test-index")
                .setTypes("test-type")
                .setSource(searchSource().query(matchAllQuery()).buildAsBytes()).get();

        assertThat(sr.getHits().totalHits(), equalTo(1L));
    }

    @Test @Repeat(iterations = 10)
    public void testParser() throws Exception {
        XContentBuilder builder = jsonBuilder();
        builder.startObject()
                .field(IndexAction.Parser.INDEX_FIELD.getPreferredName(), "test-index")
                .field(IndexAction.Parser.TYPE_FIELD.getPreferredName(), "test-type")
                .endObject();

        IndexAction.Parser actionParser = new IndexAction.Parser(ImmutableSettings.EMPTY, ClientProxy.of(client()));
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        IndexAction action = actionParser.parse(parser);

        assertThat(action.type, equalTo("test-type"));
        assertThat(action.index, equalTo("test-index"));
    }

    @Test @Repeat(iterations = 10)
    public void testParser_Failure() throws Exception {
        XContentBuilder builder = jsonBuilder();
        boolean useIndex = randomBoolean();
        boolean useType = randomBoolean();
        builder.startObject();
        {
            if (useIndex) {
                builder.field(IndexAction.Parser.INDEX_FIELD.getPreferredName(), "test-index");
            }
            if (useType) {
                builder.field(IndexAction.Parser.TYPE_FIELD.getPreferredName(), "test-type");
            }
        }
        builder.endObject();
        IndexAction.Parser actionParser = new IndexAction.Parser(ImmutableSettings.EMPTY, ClientProxy.of(client()));
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();
        try {
            actionParser.parse(parser);
            if (!(useIndex && useType)) {
                fail();
            }
        } catch (ActionException ae) {
            assertThat(useIndex && useType, equalTo(false));
        }
    }

    @Test @Repeat(iterations =30)
    public void testParser_Result() throws Exception {
        boolean success = randomBoolean();

        XContentBuilder builder = jsonBuilder().startObject()
                .field("success", success);
        if (success) {
            Map<String,Object> data = new HashMap<>();
            data.put("created", true);
            data.put("id", "0");
            data.put("version", 1);
            data.put("type", "test-type");
            data.put("index", "test-index");
            builder.field(IndexAction.Parser.RESPONSE_FIELD.getPreferredName(), data);
        } else {
            builder.field("reason", "_reason");
        }

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();
        IndexAction.Result result = new IndexAction.Parser(ImmutableSettings.EMPTY, ClientProxy.of(client()))
                .parseResult(parser);

        assertThat(result.success(), is(success));
        if (success) {
            Map<String, Object> responseData = result.response().data();
            assertThat(responseData.get("created"), equalTo((Object)Boolean.TRUE));
            assertThat(responseData.get("version"), equalTo((Object) 1));
            assertThat(responseData.get("type").toString(), equalTo("test-type"));
            assertThat(responseData.get("index").toString(), equalTo("test-index"));
        }  else {
            assertThat(result.reason, is("_reason"));
        }
    }

}
