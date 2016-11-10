/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.transform;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.env.Environment;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.MockScriptPlugin;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.xpack.watcher.condition.AlwaysCondition;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;
import org.elasticsearch.xpack.watcher.test.AbstractWatcherIntegrationTestCase;
import org.elasticsearch.xpack.watcher.transport.actions.put.PutWatchResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.xpack.watcher.actions.ActionBuilders.indexAction;
import static org.elasticsearch.xpack.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.searchInput;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.templateRequest;
import static org.elasticsearch.xpack.watcher.transform.TransformBuilders.chainTransform;
import static org.elasticsearch.xpack.watcher.transform.TransformBuilders.scriptTransform;
import static org.elasticsearch.xpack.watcher.transform.TransformBuilders.searchTransform;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.xpack.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

public class TransformIntegrationTests extends AbstractWatcherIntegrationTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        List<Class<? extends Plugin>> types = super.pluginTypes();
        types.add(CustomScriptPlugin.class);
        return types;
    }

    @Override
    public Settings nodeSettings(int nodeOrdinal) {
        Settings baseSettings = super.nodeSettings(nodeOrdinal);
        Path config;
        if (Environment.PATH_CONF_SETTING.exists(baseSettings)) {
            config = PathUtils.get(Environment.PATH_CONF_SETTING.get(baseSettings));
        } else {
            config = createTempDir().resolve("config");
        }
        Path scripts = config.resolve("scripts");

        try {
            Files.createDirectories(scripts);

            // When using the MockScriptPlugin we can map File scripts to inline scripts:
            // the name of the file script is used in test method while the source of the file script
            // must match a predefined script from CustomScriptPlugin.pluginScripts() method
            Files.write(scripts.resolve("my-script.painless"), "['key3' : ctx.payload.key1 + ctx.payload.key2]".getBytes("UTF-8"));
        } catch (IOException ex) {
            throw new RuntimeException("Failed to create scripts", ex);
        }

        // Set the config path so that the ScriptService will pick up the test scripts
        return Settings.builder()
                .put(baseSettings)
                .put(Environment.PATH_CONF_SETTING.getKey(), config)
                .put("script.stored", "true")
                .put("script.inline", "true")
                .build();
    }

    public static class CustomScriptPlugin extends MockScriptPlugin {

        @Override
        protected Map<String, Function<Map<String, Object>, Object>> pluginScripts() {
            Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();

            scripts.put("['key3' : ctx.payload.key1 + ctx.payload.key2]", vars -> {
                int key1 = (int) XContentMapValues.extractValue("ctx.payload.key1", vars);
                int key2 = (int) XContentMapValues.extractValue("ctx.payload.key2", vars);
                return singletonMap("key3", key1 + key2);
            });

            scripts.put("['key4' : ctx.payload.key3 + 10]", vars -> {
                int key3 = (int) XContentMapValues.extractValue("ctx.payload.key3", vars);
                return singletonMap("key4", key3 + 10);
            });

            return scripts;
        }

        @Override
        public String pluginScriptLang() {
            return WATCHER_LANG;
        }
    }

    public void testScriptTransform() throws Exception {
        final Script script;
        if (randomBoolean()) {
            logger.info("testing script transform with an inline script");
            script = new Script("['key3' : ctx.payload.key1 + ctx.payload.key2]");
        } else if (randomBoolean()) {
            logger.info("testing script transform with an indexed script");
            assertAcked(client().admin().cluster().preparePutStoredScript()
                    .setId("my-script")
                    .setScriptLang("painless")
                    .setSource(new BytesArray("{\"script\" : \"['key3' : ctx.payload.key1 + ctx.payload.key2]\"}"))
                    .get());
            script = new Script(ScriptType.STORED, "painless", "my-script", Collections.emptyMap());
        } else {
            logger.info("testing script transform with a file script");
            script = new Script(ScriptType.FILE, "painless", "my-script", Collections.emptyMap());
        }

        // put a watch that has watch level transform:
        PutWatchResponse putWatchResponse = watcherClient().preparePutWatch("_id1")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(AlwaysCondition.INSTANCE)
                        .transform(scriptTransform(script))
                        .addAction("_id", indexAction("output1", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));
        // put a watch that has a action level transform:
        putWatchResponse = watcherClient().preparePutWatch("_id2")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(AlwaysCondition.INSTANCE)
                        .addAction("_id", scriptTransform(script), indexAction("output2", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id1");
            timeWarp().scheduler().trigger("_id2");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id1", 1, false);
        assertWatchWithMinimumPerformedActionsCount("_id2", 1, false);
        refresh();

        SearchResponse response = client().prepareSearch("output1").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1L));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key3").toString(), equalTo("20"));

        response = client().prepareSearch("output2").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1L));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key3").toString(), equalTo("20"));
    }

    public void testSearchTransform() throws Exception {
        createIndex("my-condition-index", "my-payload-index");
        ensureGreen("my-condition-index", "my-payload-index");

        index("my-payload-index", "payload", "mytestresult");
        refresh();

        WatcherSearchTemplateRequest inputRequest = templateRequest(searchSource().query(matchAllQuery()), "my-condition-index");
        WatcherSearchTemplateRequest transformRequest = templateRequest(searchSource().query(matchAllQuery()), "my-payload-index");

        PutWatchResponse putWatchResponse = watcherClient().preparePutWatch("_id1")
                .setSource(watchBuilder()
                                .trigger(schedule(interval("5s")))
                                .input(searchInput(inputRequest))
                                .transform(searchTransform(transformRequest))
                                .addAction("_id", indexAction("output1", "result"))
                ).get();
        assertThat(putWatchResponse.isCreated(), is(true));
        putWatchResponse = watcherClient().preparePutWatch("_id2")
                .setSource(watchBuilder()
                                .trigger(schedule(interval("5s")))
                                .input(searchInput(inputRequest))
                                .addAction("_id", searchTransform(transformRequest), indexAction("output2", "result"))
                ).get();
        assertThat(putWatchResponse.isCreated(), is(true));

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id1");
            timeWarp().scheduler().trigger("_id2");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id1", 1, false);
        assertWatchWithMinimumPerformedActionsCount("_id2", 1, false);
        refresh();

        SearchResponse response = client().prepareSearch("output1").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1L));
        assertThat(response.getHits().getAt(0).sourceAsString(), containsString("mytestresult"));

        response = client().prepareSearch("output2").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1L));
        assertThat(response.getHits().getAt(0).sourceAsString(), containsString("mytestresult"));
    }

    public void testChainTransform() throws Exception {
        Script script1 = new Script("['key3' : ctx.payload.key1 + ctx.payload.key2]");
        Script script2 = new Script("['key4' : ctx.payload.key3 + 10]");

        // put a watch that has watch level transform:
        PutWatchResponse putWatchResponse = watcherClient().preparePutWatch("_id1")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(AlwaysCondition.INSTANCE)
                        .transform(chainTransform(scriptTransform(script1), scriptTransform(script2)))
                        .addAction("_id", indexAction("output1", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));
        // put a watch that has a action level transform:
        putWatchResponse = watcherClient().preparePutWatch("_id2")
                .setSource(watchBuilder()
                        .trigger(schedule(interval("5s")))
                        .input(simpleInput(MapBuilder.<String, Object>newMapBuilder().put("key1", 10).put("key2", 10)))
                        .condition(AlwaysCondition.INSTANCE)
                        .addAction("_id", chainTransform(scriptTransform(script1), scriptTransform(script2)),
                                indexAction("output2", "type")))
                .get();
        assertThat(putWatchResponse.isCreated(), is(true));

        if (timeWarped()) {
            timeWarp().scheduler().trigger("_id1");
            timeWarp().scheduler().trigger("_id2");
            refresh();
        }

        assertWatchWithMinimumPerformedActionsCount("_id1", 1, false);
        assertWatchWithMinimumPerformedActionsCount("_id2", 1, false);
        refresh();

        SearchResponse response = client().prepareSearch("output1").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1L));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key4").toString(), equalTo("30"));

        response = client().prepareSearch("output2").get();
        assertNoFailures(response);
        assertThat(response.getHits().getTotalHits(), greaterThanOrEqualTo(1L));
        assertThat(response.getHits().getAt(0).sourceAsMap().size(), equalTo(1));
        assertThat(response.getHits().getAt(0).sourceAsMap().get("key4").toString(), equalTo("30"));
    }

}
