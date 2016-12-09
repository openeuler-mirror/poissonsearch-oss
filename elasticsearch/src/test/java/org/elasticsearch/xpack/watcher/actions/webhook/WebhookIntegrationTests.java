/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.actions.webhook;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.test.http.MockResponse;
import org.elasticsearch.test.http.MockWebServer;
import org.elasticsearch.xpack.common.http.HttpRequestTemplate;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuth;
import org.elasticsearch.xpack.common.text.TextTemplate;
import org.elasticsearch.xpack.watcher.actions.ActionBuilders;
import org.elasticsearch.xpack.watcher.condition.AlwaysCondition;
import org.elasticsearch.xpack.watcher.history.WatchRecord;
import org.elasticsearch.xpack.watcher.support.xcontent.XContentSource;
import org.elasticsearch.xpack.watcher.test.AbstractWatcherIntegrationTestCase;
import org.junit.After;
import org.junit.Before;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.xpack.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.xContentSource;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.xpack.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class WebhookIntegrationTests extends AbstractWatcherIntegrationTestCase {

    private MockWebServer webServer = new MockWebServer();;

    @Before
    public void startWebservice() throws Exception {
        webServer.start();
    }

    @After
    public void stopWebservice() throws Exception {
        webServer.close();
    }

    public void testWebhook() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequestTemplate.Builder builder = HttpRequestTemplate.builder("localhost", webServer.getPort())
                .path(new TextTemplate("/test/_id"))
                .putParam("param1", new TextTemplate("value1"))
                .putParam("watch_id", new TextTemplate("_id"))
                .body(new TextTemplate("_body"));

        watcherClient().preparePutWatch("_id")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput("key", "value"))
                        .condition(AlwaysCondition.INSTANCE)
                        .addAction("_id", ActionBuilders.webhookAction(builder)))
                .get();

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id", 1, false);

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getQuery(),
                anyOf(equalTo("watch_id=_id&param1=value1"), equalTo("param1=value1&watch_id=_id")));

        assertThat(webServer.requests().get(0).getBody(), is("_body"));

        SearchResponse response = searchWatchRecords(b -> QueryBuilders.termQuery(WatchRecord.Field.STATE.getPreferredName(), "executed"));

        assertNoFailures(response);
        XContentSource source = xContentSource(response.getHits().getAt(0).getSourceRef());
        String body = source.getValue("result.actions.0.webhook.response.body");
        assertThat(body, notNullValue());
        assertThat(body, is("body"));
        Number status = source.getValue("result.actions.0.webhook.response.status");
        assertThat(status, notNullValue());
        assertThat(status.intValue(), is(200));
    }

    public void testWebhookWithBasicAuth() throws Exception {
        webServer.enqueue(new MockResponse().setResponseCode(200).setBody("body"));
        HttpRequestTemplate.Builder builder = HttpRequestTemplate.builder("localhost", webServer.getPort())
                .auth(new BasicAuth("_username", "_password".toCharArray()))
                .path(new TextTemplate("/test/_id"))
                .putParam("param1", new TextTemplate("value1"))
                .putParam("watch_id", new TextTemplate("_id"))
                .body(new TextTemplate("_body"));

        watcherClient().preparePutWatch("_id")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput("key", "value"))
                        .condition(AlwaysCondition.INSTANCE)
                        .addAction("_id", ActionBuilders.webhookAction(builder)))
                .get();

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id", 1, false);

        assertThat(webServer.requests(), hasSize(1));
        assertThat(webServer.requests().get(0).getUri().getQuery(),
                anyOf(equalTo("watch_id=_id&param1=value1"), equalTo("param1=value1&watch_id=_id")));
        assertThat(webServer.requests().get(0).getBody(), is("_body"));
        assertThat(webServer.requests().get(0).getHeader("Authorization"), is(("Basic X3VzZXJuYW1lOl9wYXNzd29yZA==")));
    }
}
