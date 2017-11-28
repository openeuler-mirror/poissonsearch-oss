/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.test.integration;

import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.watcher.condition.AlwaysCondition;
import org.elasticsearch.xpack.watcher.condition.CompareCondition;
import org.elasticsearch.xpack.watcher.condition.Condition;
import org.elasticsearch.xpack.watcher.execution.ExecutionState;
import org.elasticsearch.xpack.watcher.execution.TriggeredWatch;
import org.elasticsearch.xpack.watcher.execution.TriggeredWatchStore;
import org.elasticsearch.xpack.watcher.execution.Wid;
import org.elasticsearch.xpack.watcher.history.HistoryStore;
import org.elasticsearch.xpack.watcher.history.WatchRecord;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;
import org.elasticsearch.xpack.watcher.test.AbstractWatcherIntegrationTestCase;
import org.elasticsearch.xpack.watcher.transport.actions.stats.WatcherStatsResponse;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.xpack.watcher.watch.Watch;
import org.hamcrest.Matchers;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.action.support.WriteRequest.RefreshPolicy.IMMEDIATE;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.xpack.watcher.actions.ActionBuilders.indexAction;
import static org.elasticsearch.xpack.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.searchInput;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.templateRequest;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.xpack.watcher.trigger.schedule.Schedules.cron;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.joda.time.DateTimeZone.UTC;

@TestLogging("org.elasticsearch.xpack.watcher:DEBUG," +
        "org.elasticsearch.xpack.watcher.execution:TRACE")
public class BootStrapTests extends AbstractWatcherIntegrationTestCase {

    @Override
    protected boolean timeWarped() {
        return false;
    }

    @Before
    public void deleteAllWatchHistoryIndices() {
        assertAcked(client().admin().indices().prepareDelete(HistoryStore.INDEX_PREFIX + "*"));
    }

    public void testLoadMalformedWatchRecord() throws Exception {
        client().prepareIndex(Watch.INDEX, Watch.DOC_TYPE, "_id")
                .setSource(jsonBuilder().startObject()
                        .startObject(Watch.Field.TRIGGER.getPreferredName())
                        .startObject("schedule")
                        .field("cron", "0/5 * * * * ? 2050")
                        .endObject()
                        .endObject()
                        .startObject(Watch.Field.ACTIONS.getPreferredName())
                        .endObject()
                        .endObject())
                .get();

        // valid watch record:
        DateTime now = DateTime.now(UTC);
        Wid wid = new Wid("_id", now);
        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", now, now);
        Condition condition = AlwaysCondition.INSTANCE;
        String index = HistoryStore.getHistoryIndexNameForTime(now);
        client().prepareIndex(index, HistoryStore.DOC_TYPE, wid.value())
                .setSource(jsonBuilder().startObject()
                        .startObject(WatchRecord.TRIGGER_EVENT.getPreferredName())
                        .field(event.type(), event)
                        .endObject()
                        .startObject(Watch.Field.CONDITION.getPreferredName())
                        .field(condition.type(), condition)
                        .endObject()
                        .startObject(Watch.Field.INPUT.getPreferredName())
                        .startObject("none").endObject()
                        .endObject()
                        .endObject())
                .setWaitForActiveShards(ActiveShardCount.ALL)
                .setRefreshPolicy(IMMEDIATE)
                .get();

        // unknown condition:
        wid = new Wid("_id", now);
        client().prepareIndex(index, HistoryStore.DOC_TYPE, wid.value())
                .setSource(jsonBuilder().startObject()
                        .startObject(WatchRecord.TRIGGER_EVENT.getPreferredName())
                        .field(event.type(), event)
                        .endObject()
                        .startObject(Watch.Field.CONDITION.getPreferredName())
                        .startObject("unknown").endObject()
                        .endObject()
                        .startObject(Watch.Field.INPUT.getPreferredName())
                        .startObject("none").endObject()
                        .endObject()
                        .endObject())
                .setWaitForActiveShards(ActiveShardCount.ALL)
                .setRefreshPolicy(IMMEDIATE)
                .get();

        // unknown trigger:
        wid = new Wid("_id", now);
        client().prepareIndex(index, HistoryStore.DOC_TYPE, wid.value())
                .setSource(jsonBuilder().startObject()
                        .startObject(WatchRecord.TRIGGER_EVENT.getPreferredName())
                        .startObject("unknown").endObject()
                        .endObject()
                        .startObject(Watch.Field.CONDITION.getPreferredName())
                        .field(condition.type(), condition)
                        .endObject()
                        .startObject(Watch.Field.INPUT.getPreferredName())
                        .startObject("none").endObject()
                        .endObject()
                        .endObject())
                .setWaitForActiveShards(ActiveShardCount.ALL)
                .setRefreshPolicy(IMMEDIATE)
                .get();

        ensureWatcherStarted();
        stopWatcher();
        startWatcher();

        WatcherStatsResponse response = watcherClient().prepareWatcherStats().get();
        assertThat(response.getWatchesCount(), equalTo(1L));
    }

    public void testLoadExistingWatchesUponStartup() throws Exception {
        ensureWatcherStarted();
        stopWatcher();

        int numWatches = scaledRandomIntBetween(16, 128);
        WatcherSearchTemplateRequest request =
                templateRequest(searchSource().query(termQuery("field", "value")), "my-index");

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        for (int i = 0; i < numWatches; i++) {
            bulkRequestBuilder.add(
                    client().prepareIndex(Watch.INDEX, Watch.DOC_TYPE, "_id" + i)
                            .setSource(watchBuilder()
                                    .trigger(schedule(cron("0 0/5 * * * ? 2050")))
                                    .input(searchInput(request))
                                    .condition(new CompareCondition("ctx.payload.hits.total", CompareCondition.Op.EQ, 1L))
                                    .buildAsBytes(XContentType.JSON), XContentType.JSON
                            )
                            .setWaitForActiveShards(ActiveShardCount.ALL));
        }
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();
        assertHitCount(client().prepareSearch(Watch.INDEX).setSize(0).get(), numWatches);

        startWatcher();

        assertBusy(() -> {
            WatcherStatsResponse response = watcherClient().prepareWatcherStats().get();
            assertThat(response.getWatchesCount(), equalTo((long) numWatches));
        });
    }

    public void testMixedTriggeredWatchLoading() throws Exception {
        createIndex("output");
        client().prepareIndex("my-index", "foo", "bar")
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field", "value").get();

        WatcherStatsResponse response = watcherClient().prepareWatcherStats().get();
        assertThat(response.getWatchesCount(), equalTo(0L));

        WatcherSearchTemplateRequest request = templateRequest(searchSource().query(termQuery("field", "value")), "my-index");

        int numWatches = 8;
        for (int i = 0; i < numWatches; i++) {
            String watchId = "_id" + i;
            watcherClient().preparePutWatch(watchId).setSource(watchBuilder()
                    .trigger(schedule(cron("0/5 * * * * ? 2050")))
                    .input(searchInput(request))
                    .condition(AlwaysCondition.INSTANCE)
                    .addAction("_id", indexAction("output", "test"))
                    .defaultThrottlePeriod(TimeValue.timeValueMillis(0))
            ).get();
        }

        ensureWatcherStarted();
        stopWatcher();

        DateTime now = DateTime.now(UTC);
        final int numRecords = scaledRandomIntBetween(numWatches, 128);
        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        for (int i = 0; i < numRecords; i++) {
            String watchId = "_id" + (i % numWatches);
            now = now.plusMinutes(1);
            ScheduleTriggerEvent event = new ScheduleTriggerEvent(watchId, now, now);
            Wid wid = new Wid(watchId, now);
            TriggeredWatch triggeredWatch = new TriggeredWatch(wid, event);
            bulkRequestBuilder.add(
                    client().prepareIndex(TriggeredWatchStore.INDEX_NAME, TriggeredWatchStore.DOC_TYPE, triggeredWatch.id().value())
                            .setSource(jsonBuilder().value(triggeredWatch))
                            .request());
        }
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        logger.info("Added [{}] triggered watches for [{}] different watches, starting watcher again", numRecords, numWatches);
        startWatcher();
        assertSingleExecutionAndCompleteWatchHistory(numWatches, numRecords);
    }

    public void testTriggeredWatchLoading() throws Exception {
        createIndex("output");
        client().prepareIndex("my-index", "foo", "bar")
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .setSource("field", "value").get();

        WatcherStatsResponse response = watcherClient().prepareWatcherStats().get();
        assertThat(response.getWatchesCount(), equalTo(0L));

        String watchId = "_id";
        WatcherSearchTemplateRequest request = templateRequest(searchSource().query(termQuery("field", "value")), "my-index");
        watcherClient().preparePutWatch(watchId).setSource(watchBuilder()
                .trigger(schedule(cron("0/5 * * * * ? 2050")))
                .input(searchInput(request))
                .condition(AlwaysCondition.INSTANCE)
                .addAction("_id", indexAction("output", "test"))
                .defaultThrottlePeriod(TimeValue.timeValueMillis(0))
        ).get();

        ensureWatcherStarted();
        stopWatcher();

        DateTime now = DateTime.now(UTC);
        final int numRecords = scaledRandomIntBetween(2, 12);
        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        for (int i = 0; i < numRecords; i++) {
            now = now.plusMinutes(1);
            ScheduleTriggerEvent event = new ScheduleTriggerEvent(watchId, now, now);
            Wid wid = new Wid(watchId, now);
            TriggeredWatch triggeredWatch = new TriggeredWatch(wid, event);
            bulkRequestBuilder.add(client()
                    .prepareIndex(TriggeredWatchStore.INDEX_NAME, TriggeredWatchStore.DOC_TYPE, triggeredWatch.id().value())
                    .setSource(jsonBuilder().value(triggeredWatch))
                    .setWaitForActiveShards(ActiveShardCount.ALL)
            );
        }
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get();

        startWatcher();

        assertSingleExecutionAndCompleteWatchHistory(1, numRecords);
    }

    private void assertSingleExecutionAndCompleteWatchHistory(final long numberOfWatches,
                                                              final int expectedWatchHistoryCount) throws Exception {
        assertBusy(() -> {
            // We need to wait until all the records are processed from the internal execution queue, only then we can assert
            // that numRecords watch records have been processed as part of starting up.
            WatcherStatsResponse response = watcherClient().prepareWatcherStats().setIncludeCurrentWatches(true).get();
            long maxSize = response.getNodes().stream().map(WatcherStatsResponse.Node::getSnapshots).mapToLong(List::size).sum();
            assertThat(maxSize, equalTo(0L));

            refresh();
            SearchResponse searchResponse = client().prepareSearch("output").get();
            assertThat(searchResponse.getHits().getTotalHits(), is(greaterThanOrEqualTo(numberOfWatches)));
            long successfulWatchExecutions = searchResponse.getHits().getTotalHits();

            // the watch history should contain entries for each triggered watch, which a few have been marked as not executed
            SearchResponse historySearchResponse = client().prepareSearch(HistoryStore.INDEX_PREFIX + "*").setSize(10000).get();
            assertHitCount(historySearchResponse, expectedWatchHistoryCount);
            long notExecutedCount = Arrays.stream(historySearchResponse.getHits().getHits())
                    .filter(hit -> hit.getSourceAsMap().get("state").equals(ExecutionState.NOT_EXECUTED_ALREADY_QUEUED.id()))
                    .count();
            logger.info("Watches not executed: [{}]: expected watch history count [{}] - [{}] successful watch exections",
                    notExecutedCount, expectedWatchHistoryCount, successfulWatchExecutions);
            assertThat(notExecutedCount, is(expectedWatchHistoryCount - successfulWatchExecutions));
        }, 20, TimeUnit.SECONDS);
    }

    public void testManuallyStopped() throws Exception {
        ensureWatcherStarted();
        WatcherStatsResponse response = watcherClient().prepareWatcherStats().get();
        assertThat(response.watcherMetaData().manuallyStopped(), is(false));
        stopWatcher();
        response = watcherClient().prepareWatcherStats().get();
        assertThat(response.watcherMetaData().manuallyStopped(), is(true));
        startWatcher();
        response = watcherClient().prepareWatcherStats().get();
        assertThat(response.watcherMetaData().manuallyStopped(), is(false));
    }

    public void testWatchRecordSavedTwice() throws Exception {
        // Watcher could prevent to start if a watch record tried to executed twice or more and the watch didn't exist
        // for that watch record or the execution threadpool rejected the watch record.
        // A watch record without a watch is the easiest to simulate, so that is what this test does.
        if (client().admin().indices().prepareExists(Watch.INDEX).get().isExists() == false) {
            // we rarely create an .watches alias in the base class
            assertAcked(client().admin().indices().prepareCreate(Watch.INDEX));
        }
        DateTime triggeredTime = new DateTime(2015, 11, 5, 0, 0, 0, 0, DateTimeZone.UTC);
        final String watchRecordIndex = HistoryStore.getHistoryIndexNameForTime(triggeredTime);

        logger.info("Stopping watcher");
        ensureWatcherStarted();
        stopWatcher();

        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        int numRecords = scaledRandomIntBetween(8, 32);
        for (int i = 0; i < numRecords; i++) {
            String watchId = Integer.toString(i);
            ScheduleTriggerEvent event = new ScheduleTriggerEvent(watchId, triggeredTime, triggeredTime);
            Wid wid = new Wid(watchId, triggeredTime);
            TriggeredWatch triggeredWatch = new TriggeredWatch(wid, event);
            bulkRequestBuilder.add(
                    client().prepareIndex(TriggeredWatchStore.INDEX_NAME, TriggeredWatchStore.DOC_TYPE, triggeredWatch.id().value())
                            .setSource(jsonBuilder().value(triggeredWatch))
            );

            String id = internalCluster().getInstance(ClusterService.class).localNode().getId();
            WatchRecord watchRecord = new WatchRecord.MessageWatchRecord(wid, event, ExecutionState.EXECUTED, "executed", id);
            bulkRequestBuilder.add(client().prepareIndex(watchRecordIndex, HistoryStore.DOC_TYPE, watchRecord.id().value())
                    .setSource(jsonBuilder().value(watchRecord))
            );
        }
        assertNoFailures(bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE).get());

        logger.info("Starting watcher");
        startWatcher();

        assertBusy(() -> {
            // We need to wait until all the records are processed from the internal execution queue, only then we can assert
            // that numRecords watch records have been processed as part of starting up.
            WatcherStatsResponse response = watcherClient().prepareWatcherStats().setIncludeCurrentWatches(true).get();
            long maxSize = response.getNodes().stream().map(WatcherStatsResponse.Node::getSnapshots).mapToLong(List::size).sum();
            assertThat(maxSize, equalTo(0L));

            // but even then since the execution of the watch record is async it may take a little bit before
            // the actual documents are in the output index
            refresh();
            SearchResponse searchResponse = client().prepareSearch(watchRecordIndex).setSize(numRecords).get();
            assertThat(searchResponse.getHits().getTotalHits(), Matchers.equalTo((long) numRecords));
            for (int i = 0; i < numRecords; i++) {
                assertThat(searchResponse.getHits().getAt(i).getSourceAsMap().get("state"),
                        is(ExecutionState.EXECUTED.id()));
            }
        });
    }
}
