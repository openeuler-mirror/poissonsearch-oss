/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.condition;


import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.env.Environment;
import org.elasticsearch.script.GeneralScriptException;
import org.elasticsearch.script.MockScriptEngine;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptContextRegistry;
import org.elasticsearch.script.ScriptEngineRegistry;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.ScriptException;
import org.elasticsearch.script.ScriptMetaData;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.ScriptSettings;
import org.elasticsearch.script.ScriptType;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.test.AbstractWatcherIntegrationTestCase;
import org.elasticsearch.xpack.watcher.watch.Payload;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;

import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.script.ScriptService.SCRIPT_AUTO_RELOAD_ENABLED_SETTING;
import static org.elasticsearch.xpack.watcher.support.Exceptions.illegalArgument;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.mockExecutionContext;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class ScriptConditionTests extends ESTestCase {

    private ScriptService scriptService;

    @Before
    public void init() throws IOException {
        Map<String, Function<Map<String, Object>, Object>> scripts = new HashMap<>();
        scripts.put("return true", s -> true);
        scripts.put("return new Object()", s -> new Object());

        scripts.put("ctx.trigger.scheduled_time.getMillis() < new Date().time", vars -> {
            DateTime scheduledTime = (DateTime) XContentMapValues.extractValue("ctx.trigger.scheduled_time", vars);
            return scheduledTime.getMillis() < new Date().getTime();
        });

        scripts.put("null.foo", s -> {
            throw new ScriptException("Error evaluating null.foo", new IllegalArgumentException(), emptyList(),
                    "null.foo", AbstractWatcherIntegrationTestCase.WATCHER_LANG);
        });

        scripts.put("ctx.payload.hits.total > 1", vars -> {
            int total = (int) XContentMapValues.extractValue("ctx.payload.hits.total", vars);
            return total > 1;
        });

        scripts.put("ctx.payload.hits.total > threshold", vars -> {
            int total = (int) XContentMapValues.extractValue("ctx.payload.hits.total", vars);
            int threshold = (int) XContentMapValues.extractValue("threshold", vars);
            return total > threshold;
        });

        ScriptEngineService engine = new MockScriptEngine(MockScriptEngine.NAME, scripts);

        ScriptEngineRegistry registry = new ScriptEngineRegistry(singleton(engine));
        ScriptContextRegistry contextRegistry = new ScriptContextRegistry(singleton(new ScriptContext.Plugin("xpack", "watch")));
        ScriptSettings scriptSettings = new ScriptSettings(registry, contextRegistry);

        Settings settings = Settings.builder()
                .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir())
                .build();

        scriptService = new ScriptService(settings, new Environment(settings), null, registry, contextRegistry, scriptSettings);
        ClusterState.Builder clusterState = new ClusterState.Builder(new ClusterName("_name"));
        clusterState.metaData(MetaData.builder().putCustom(ScriptMetaData.TYPE, new ScriptMetaData.Builder(null).build()));
        ClusterState cs = clusterState.build();
        scriptService.clusterChanged(new ClusterChangedEvent("_source", cs, cs));
    }

    public void testExecute() throws Exception {
        ScriptCondition condition = new ScriptCondition(mockScript("ctx.payload.hits.total > 1"), scriptService);
        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500L, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));
        assertFalse(condition.execute(ctx).met());
    }

    public void testExecuteMergedParams() throws Exception {
        Script script = new Script(ScriptType.INLINE, "mockscript", "ctx.payload.hits.total > threshold", singletonMap("threshold", 1));
        ScriptCondition executable = new ScriptCondition(script, scriptService);
        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500L, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));
        assertFalse(executable.execute(ctx).met());
    }

    public void testParserValid() throws Exception {

        XContentBuilder builder = createConditionContent("ctx.payload.hits.total > 1", "mockscript", ScriptType.INLINE);

        XContentParser parser = createParser(builder);
        parser.nextToken();
        ScriptCondition executable = ScriptCondition.parse(scriptService, "_watch", parser);

        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500L, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));

        assertFalse(executable.execute(ctx).met());


        builder = createConditionContent("return true", "mockscript", ScriptType.INLINE);
        parser = createParser(builder);
        parser.nextToken();
        executable = ScriptCondition.parse(scriptService, "_watch", parser);

        ctx = mockExecutionContext("_name", new Payload.XContent(response));

        assertTrue(executable.execute(ctx).met());
    }

    public void testParserInvalid() throws Exception {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        builder.startObject().endObject();
        XContentParser parser = createParser(builder);
        parser.nextToken();
        try {
            ScriptCondition.parse(scriptService, "_id", parser);
            fail("expected a condition exception trying to parse an invalid condition XContent");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(),
                    containsString("must specify either code for an [inline] script or an id for a [stored] script or [file] script"));
        }
    }

    public void testScriptConditionParserBadScript() throws Exception {
        ScriptType scriptType = randomFrom(ScriptType.values());
        String script;
        Class<? extends Exception> expectedException;
        switch (scriptType) {
            case STORED:
                expectedException = ResourceNotFoundException.class;
                script = "nonExisting_script";
                break;
            case FILE:
                expectedException = IllegalArgumentException.class;
                script = "nonExisting_script";
                break;
            default:
                expectedException = GeneralScriptException.class;
                script = "foo = = 1";
        }
        XContentBuilder builder = createConditionContent(script, "mockscript", scriptType);
        XContentParser parser = createParser(builder);
        parser.nextToken();

        expectThrows(expectedException,
                () -> ScriptCondition.parse(scriptService, "_watch", parser));
    }

    public void testScriptConditionParser_badLang() throws Exception {
        String script = "return true";
        XContentBuilder builder = createConditionContent(script, "not_a_valid_lang", ScriptType.INLINE);
        XContentParser parser = createParser(builder);
        parser.nextToken();
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class,
                () -> ScriptCondition.parse(scriptService, "_watch", parser));
        assertThat(exception.getMessage(), containsString("script_lang not supported [not_a_valid_lang]"));
    }

    public void testScriptConditionThrowException() throws Exception {
        ScriptCondition condition = new ScriptCondition(
                mockScript("null.foo"), scriptService);
        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500L, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));
        ScriptException exception = expectThrows(ScriptException.class, () -> condition.execute(ctx));
        assertThat(exception.getMessage(), containsString("Error evaluating null.foo"));
    }

    public void testScriptConditionReturnObjectThrowsException() throws Exception {
        ScriptCondition condition = new ScriptCondition(mockScript("return new Object()"), scriptService);
        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500L, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));
        Exception exception = expectThrows(IllegalStateException.class, () -> condition.execute(ctx));
        assertThat(exception.getMessage(),
                containsString("condition [script] must return a boolean value (true|false) but instead returned [_name]"));
    }

    public void testScriptConditionAccessCtx() throws Exception {
        ScriptCondition condition = new ScriptCondition(mockScript("ctx.trigger.scheduled_time.getMillis() < new Date().time"),
                scriptService);
        SearchResponse response = new SearchResponse(InternalSearchResponse.empty(), "", 3, 3, 500L, new ShardSearchFailure[0]);
        WatchExecutionContext ctx = mockExecutionContext("_name", new DateTime(DateTimeZone.UTC), new Payload.XContent(response));
        Thread.sleep(10);
        assertThat(condition.execute(ctx).met(), is(true));
    }

    private static XContentBuilder createConditionContent(String script, String scriptLang, ScriptType scriptType) throws IOException {
        XContentBuilder builder = jsonBuilder();
        if (scriptType == null) {
            return builder.value(script);
        }
        builder.startObject();
        switch (scriptType) {
            case INLINE:
                builder.field("inline", script);
                break;
            case FILE:
                builder.field("file", script);
                break;
            case STORED:
                builder.field("stored", script);
                break;
            default:
                throw illegalArgument("unsupported script type [{}]", scriptType);
        }
        if (scriptLang != null && scriptType != ScriptType.STORED) {
            builder.field("lang", scriptLang);
        }
        return builder.endObject();
    }
}
