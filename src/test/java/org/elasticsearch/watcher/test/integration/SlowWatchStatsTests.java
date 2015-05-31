/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.test.integration;

import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.watcher.WatcherState;
import org.elasticsearch.watcher.actions.ActionBuilders;
import org.elasticsearch.watcher.condition.ConditionBuilders;
import org.elasticsearch.watcher.execution.ExecutionPhase;
import org.elasticsearch.watcher.execution.QueuedWatch;
import org.elasticsearch.watcher.input.InputBuilders;
import org.elasticsearch.watcher.test.AbstractWatcherIntegrationTests;
import org.elasticsearch.watcher.transport.actions.stats.WatcherStatsResponse;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.TEST;
import static org.elasticsearch.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.*;

/**
 */
@LuceneTestCase.Slow
@ElasticsearchIntegrationTest.ClusterScope(scope = TEST, numClientNodes = 0, transportClientRatio = 0, randomDynamicTemplates = false, numDataNodes = 2)
public class SlowWatchStatsTests extends AbstractWatcherIntegrationTests {

    @Override
    protected boolean timeWarped() {
        return false;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                // So it is predictable how many slow watches we need to add to accumulate pending watches
                .put(EsExecutors.PROCESSORS, "1")
                .build();
    }

    @Test
    public void testCurrentWatches() throws Exception {
        watcherClient().preparePutWatch("_id").setSource(watchBuilder()
                .trigger(schedule(interval("1s")))
                .input(InputBuilders.simpleInput("key", "value"))
                .condition(ConditionBuilders.scriptCondition("sleep 10000; return true"))
                .addAction("_action", ActionBuilders.loggingAction("hello {{ctx.watch_id}}!"))
        ).get();

        assertBusy(new Runnable() {
            @Override
            public void run() {
                WatcherStatsResponse response = watcherClient().prepareWatcherStats().setIncludeCurrentWatches(true).get();
                assertThat(response.getWatcherState(), equalTo(WatcherState.STARTED));
                assertThat(response.getWatchesCount(), equalTo(1l));
                assertThat(response.getQueuedWatches(), nullValue());
                assertThat(response.getSnapshots(), notNullValue());
                assertThat(response.getSnapshots().size(), equalTo(1));
                assertThat(response.getSnapshots().get(0).watchId(), equalTo("_id"));
                assertThat(response.getSnapshots().get(0).executionPhase(), equalTo(ExecutionPhase.CONDITION));
            }
        });
    }

    @Test
    public void testPendingWatches() throws Exception {
        // Add 5 slow watches and we should almost immediately see pending watches in the stats api
        for (int i = 0; i < 5; i++) {
            watcherClient().preparePutWatch("_id" + i).setSource(watchBuilder()
                            .trigger(schedule(interval("1s")))
                            .input(InputBuilders.simpleInput("key", "value"))
                            .condition(ConditionBuilders.scriptCondition("sleep 10000; return true"))
                            .addAction("_action", ActionBuilders.loggingAction("hello {{ctx.watch_id}}!"))
            ).get();
        }

        assertBusy(new Runnable() {
            @Override
            public void run() {
                WatcherStatsResponse response = watcherClient().prepareWatcherStats().setIncludeQueuedWatches(true).get();
                assertThat(response.getWatcherState(), equalTo(WatcherState.STARTED));
                assertThat(response.getWatchesCount(), equalTo(5l));
                assertThat(response.getSnapshots(), nullValue());
                assertThat(response.getQueuedWatches(), notNullValue());
                assertThat(response.getQueuedWatches().size(), greaterThanOrEqualTo(5));
                DateTime previous = null;
                for (QueuedWatch queuedWatch : response.getQueuedWatches()) {
                    assertThat(queuedWatch.watchId(), anyOf(equalTo("_id0"), equalTo("_id1"), equalTo("_id2"), equalTo("_id3"), equalTo("_id4")));
                    if (previous != null) {
                        // older pending watch should be on top:
                        assertThat(previous.getMillis(), lessThanOrEqualTo(queuedWatch.executionTime().getMillis()));
                    }
                    previous = queuedWatch.executionTime();
                }
            }
        }, 60, TimeUnit.SECONDS);
    }

}
