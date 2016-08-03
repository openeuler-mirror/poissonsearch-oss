/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.input.chain;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.MockNetty3Plugin;
import org.elasticsearch.xpack.MockNetty4Plugin;
import org.elasticsearch.xpack.watcher.input.http.HttpInput;
import org.elasticsearch.xpack.common.http.HttpRequestTemplate;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuth;
import org.elasticsearch.xpack.watcher.test.AbstractWatcherIntegrationTestCase;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.xpack.watcher.actions.ActionBuilders.indexAction;
import static org.elasticsearch.xpack.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.chainInput;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.httpInput;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.xpack.watcher.trigger.schedule.IntervalSchedule.Interval.Unit.SECONDS;
import static org.elasticsearch.xpack.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.containsString;

public class ChainIntegrationTests extends AbstractWatcherIntegrationTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(NetworkModule.HTTP_ENABLED.getKey(), true)
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        ArrayList<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(MockNetty3Plugin.class); // for http
        plugins.add(MockNetty4Plugin.class); // for http
        return plugins;
    }

    public void testChainedInputsAreWorking() throws Exception {
        String index = "the-most-awesome-index-ever";
        createIndex(index);
        client().prepareIndex(index, "type", "id").setSource("{}").setRefreshPolicy(IMMEDIATE).get();

        InetSocketAddress address = internalCluster().httpAddresses()[0];
        HttpInput.Builder httpInputBuilder = httpInput(HttpRequestTemplate.builder(address.getHostString(), address.getPort())
                .path("/" + index  + "/_search")
                .body(jsonBuilder().startObject().field("size", 1).endObject())
                .auth(securityEnabled() ? new BasicAuth("test", "changeme".toCharArray()) : null));

        ChainInput.Builder chainedInputBuilder = chainInput()
                .add("first", simpleInput("url", "/" + index  + "/_search"))
                .add("second", httpInputBuilder);

        watcherClient().preparePutWatch("_name")
                .setSource(watchBuilder()
                        .trigger(schedule(interval(5, SECONDS)))
                        .input(chainedInputBuilder)
                        .addAction("indexAction", indexAction("my-index", "my-type")))
                .get();

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_name");
            refresh();
        } else {
            assertBusy(new Runnable() {
                @Override
                public void run() {
                    assertWatchExecuted();
                }
            }, 9, TimeUnit.SECONDS);
        }

        assertWatchWithMinimumPerformedActionsCount("_name", 1, false);
    }

    public void assertWatchExecuted() {
        try {
            refresh();
            SearchResponse searchResponse = client().prepareSearch("my-index").setTypes("my-type").get();
            assertHitCount(searchResponse, 1);
            assertThat(searchResponse.getHits().getAt(0).sourceAsString(), containsString("the-most-awesome-index-ever"));
        } catch (IndexNotFoundException e) {
            fail("Index not found: ["+ e.getIndex() + "]");
        }
    }
}
