/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.messy.tests;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.Index;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.groovy.GroovyPlugin;
import org.elasticsearch.search.SearchShardTarget;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.DateHistogramInterval;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.common.ScriptServiceProxy;
import org.elasticsearch.xpack.watcher.condition.script.ExecutableScriptCondition;
import org.elasticsearch.xpack.watcher.condition.script.ScriptCondition;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.support.Script;
import org.elasticsearch.xpack.watcher.test.AbstractWatcherIntegrationTestCase;
import org.elasticsearch.xpack.watcher.watch.Payload;
import org.junit.After;
import org.junit.Before;

import java.util.List;

import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.mockExecutionContext;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.when;

/**
 */
public class ScriptConditionSearchIT extends AbstractWatcherIntegrationTestCase {
    private ThreadPool tp = null;
    private ScriptServiceProxy scriptService;

    @Override
    protected List<Class<? extends Plugin>> pluginTypes() {
        List<Class<? extends Plugin>> types = super.pluginTypes();
        types.add(GroovyPlugin.class);
        return types;
    }

    @Before
    public void init() throws Exception {
        tp = new TestThreadPool(ThreadPool.Names.SAME);
        scriptService = MessyTestUtils.getScriptServiceProxy(tp);
    }

    @After
    public void cleanup() {
        tp.shutdownNow();
    }

    public void testExecuteWithAggs() throws Exception {
        client().prepareIndex("my-index", "my-type").setSource("@timestamp", "2005-01-01T00:00").get();
        client().prepareIndex("my-index", "my-type").setSource("@timestamp", "2005-01-01T00:10").get();
        client().prepareIndex("my-index", "my-type").setSource("@timestamp", "2005-01-01T00:20").get();
        client().prepareIndex("my-index", "my-type").setSource("@timestamp", "2005-01-01T00:30").get();
        refresh();

        SearchResponse response = client().prepareSearch("my-index")
                .addAggregation(AggregationBuilders.dateHistogram("rate").field("@timestamp")
                        .dateHistogramInterval(DateHistogramInterval.HOUR).order(Histogram.Order.COUNT_DESC))
                .get();

        ExecutableScriptCondition condition = new ExecutableScriptCondition(
                new ScriptCondition(Script.inline("ctx.payload.aggregations.rate.buckets[0]?.doc_count >= 5").build()),
                logger, scriptService);

        WatchExecutionContext ctx = mockExecutionContext("_name", new Payload.XContent(response));
        assertFalse(condition.execute(ctx).met());

        client().prepareIndex("my-index", "my-type").setSource("@timestamp", "2005-01-01T00:40").get();
        refresh();

        response = client().prepareSearch("my-index").addAggregation(AggregationBuilders.dateHistogram("rate").field("@timestamp")
                .dateHistogramInterval(DateHistogramInterval.HOUR).order(Histogram.Order.COUNT_DESC))
                .get();

        ctx = mockExecutionContext("_name", new Payload.XContent(response));
        assertThat(condition.execute(ctx).met(), is(true));
    }

    public void testExecuteAccessHits() throws Exception {
        ExecutableScriptCondition condition = new ExecutableScriptCondition(new ScriptCondition(
                Script.inline("ctx.payload.hits?.hits[0]?._score == 1.0").build()), logger, scriptService);
        InternalSearchHit hit = new InternalSearchHit(0, "1", new Text("type"), null);
        hit.score(1f);
        hit.shard(new SearchShardTarget("a", new Index("a", "testUUID"), 0));

        InternalSearchResponse internalSearchResponse = new InternalSearchResponse(new InternalSearchHits(
                new InternalSearchHit[]{hit}, 1L, 1f), null, null, null, false, false);
        SearchResponse response = new SearchResponse(internalSearchResponse, "", 3, 3, 500L, new ShardSearchFailure[0]);

        WatchExecutionContext ctx = mockExecutionContext("_watch_name", new Payload.XContent(response));
        assertThat(condition.execute(ctx).met(), is(true));
        hit.score(2f);
        when(ctx.payload()).thenReturn(new Payload.XContent(response));
        assertThat(condition.execute(ctx).met(), is(false));
    }
}
