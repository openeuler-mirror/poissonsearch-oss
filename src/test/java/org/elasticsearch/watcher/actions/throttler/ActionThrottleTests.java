/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.throttler;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.apache.lucene.util.LuceneTestCase.Slow;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.watcher.actions.Action;
import org.elasticsearch.watcher.actions.ActionWrapper;
import org.elasticsearch.watcher.actions.email.EmailAction;
import org.elasticsearch.watcher.actions.email.service.EmailTemplate;
import org.elasticsearch.watcher.actions.index.IndexAction;
import org.elasticsearch.watcher.actions.logging.LoggingAction;
import org.elasticsearch.watcher.actions.webhook.WebhookAction;
import org.elasticsearch.watcher.client.WatchSourceBuilder;
import org.elasticsearch.watcher.execution.ActionExecutionMode;
import org.elasticsearch.watcher.execution.ExecutionState;
import org.elasticsearch.watcher.execution.ManualExecutionContext;
import org.elasticsearch.watcher.execution.WatchExecutionResult;
import org.elasticsearch.watcher.history.WatchRecord;
import org.elasticsearch.watcher.support.clock.SystemClock;
import org.elasticsearch.watcher.support.http.HttpRequestTemplate;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.test.AbstractWatcherIntegrationTests;
import org.elasticsearch.watcher.transport.actions.execute.ExecuteWatchResponse;
import org.elasticsearch.watcher.transport.actions.get.GetWatchRequest;
import org.elasticsearch.watcher.transport.actions.put.PutWatchRequest;
import org.elasticsearch.watcher.transport.actions.put.PutWatchResponse;
import org.elasticsearch.watcher.trigger.manual.ManualTriggerEvent;
import org.elasticsearch.watcher.trigger.schedule.IntervalSchedule;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTrigger;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.junit.Test;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.elasticsearch.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.*;

/**
 */
public class ActionThrottleTests extends AbstractWatcherIntegrationTests {

    @Override
    protected boolean timeWarped() {
        return true;
    }

    @Test @Slow @Repeat(iterations = 10)
    public void testSingleActionAckThrottle() throws Exception {
        boolean useClientForAcking = randomBoolean();

        WatchSourceBuilder watchSourceBuilder = watchBuilder()
                .trigger(schedule(interval("60m")));

        AvailableAction availableAction = randomFrom(AvailableAction.values());
        Action.Builder action = availableAction.action();
        watchSourceBuilder.addAction("test_id", action);

        PutWatchResponse putWatchResponse = watcherClient().putWatch(new PutWatchRequest("_id", watchSourceBuilder)).actionGet();
        assertThat(putWatchResponse.getVersion(), greaterThan(0L));
        refresh();

        assertThat(watcherClient().prepareGetWatch("_id").get().isFound(), equalTo(true));
        ManualExecutionContext ctx = getManualExecutionContext(new TimeValue(0, TimeUnit.SECONDS));
        WatchRecord watchRecord = executionService().execute(ctx);

        assertThat(watchRecord.execution().actionsResults().get("test_id").action().status(), equalTo(Action.Result.Status.SIMULATED));
        if (timeWarped()) {
            timeWarp().clock().fastForward(TimeValue.timeValueSeconds(1));
        }
        boolean ack = randomBoolean();
        if (ack) {
            if (useClientForAcking) {
                watcherClient().prepareAckWatch("_id").setActionIds("test_id").get();
            } else {
                watchService().ackWatch("_id", new String[] { "test_id" }, new TimeValue(5, TimeUnit.SECONDS));
            }
        }
        ctx = getManualExecutionContext(new TimeValue(0, TimeUnit.SECONDS));
        watchRecord = executionService().execute(ctx);
        if (ack) {
            assertThat(watchRecord.execution().actionsResults().get("test_id").action().status(), equalTo(Action.Result.Status.THROTTLED));
        } else {
            assertThat(watchRecord.execution().actionsResults().get("test_id").action().status(), equalTo(Action.Result.Status.SIMULATED));
        }
    }

    @Test @Slow @Repeat(iterations = 10)
    public void testRandomMultiActionAckThrottle() throws Exception {
        boolean useClientForAcking = randomBoolean();

        WatchSourceBuilder watchSourceBuilder = watchBuilder()
                .trigger(schedule(interval("60m")));

        Set<String> ackingActions = new HashSet<>();
        for (int i = 0; i < scaledRandomIntBetween(5,10); ++i) {
            AvailableAction availableAction = randomFrom(AvailableAction.values());
            Action.Builder action = availableAction.action();
            watchSourceBuilder.addAction("test_id" + i, action);
            if (randomBoolean()) {
                ackingActions.add("test_id" + i);
            }
        }

        PutWatchResponse putWatchResponse = watcherClient().putWatch(new PutWatchRequest("_id", watchSourceBuilder)).actionGet();
        assertThat(putWatchResponse.getVersion(), greaterThan(0L));
        refresh();

        assertThat(watcherClient().getWatch(new GetWatchRequest("_id")).actionGet().isFound(), equalTo(true));
        ManualExecutionContext ctx = getManualExecutionContext(new TimeValue(0, TimeUnit.SECONDS));
        executionService().execute(ctx);

        for (String actionId : ackingActions)  {
            if (useClientForAcking) {
                watcherClient().prepareAckWatch("_id").setActionIds(actionId).get();
            } else {
                watchService().ackWatch("_id", new String[]{actionId}, new TimeValue(5, TimeUnit.SECONDS));
            }
        }

        if (timeWarped()) {
            timeWarp().clock().fastForwardSeconds(5);
        }

        ctx = getManualExecutionContext(new TimeValue(0, TimeUnit.SECONDS));
        WatchRecord watchRecord = executionService().execute(ctx);
        for (ActionWrapper.Result result : watchRecord.execution().actionsResults()) {
            if (ackingActions.contains(result.id())) {
                assertThat(result.action().status(), equalTo(Action.Result.Status.THROTTLED));
            } else {
                assertThat(result.action().status(), equalTo(Action.Result.Status.SIMULATED));
            }
        }
    }

    @Test @Slow
    public void testDifferentThrottlePeriods() throws Exception {
        WatchSourceBuilder watchSourceBuilder = watchBuilder()
                .trigger(schedule(interval("60m")));

        watchSourceBuilder.addAction("ten_sec_throttle", new TimeValue(10, TimeUnit.SECONDS), randomFrom(AvailableAction.values()).action());
        watchSourceBuilder.addAction("fifteen_sec_throttle", new TimeValue(15, TimeUnit.SECONDS), randomFrom(AvailableAction.values()).action());

        PutWatchResponse putWatchResponse = watcherClient().putWatch(new PutWatchRequest("_id", watchSourceBuilder)).actionGet();
        assertThat(putWatchResponse.getVersion(), greaterThan(0L));
        refresh();
        assertThat(watcherClient().getWatch(new GetWatchRequest("_id")).actionGet().isFound(), equalTo(true));

        if (timeWarped()) {
            timeWarp().clock().setTime(new DateTime(UTC));
        }

        ManualExecutionContext ctx = getManualExecutionContext(new TimeValue(0, TimeUnit.SECONDS));
        WatchRecord watchRecord = executionService().execute(ctx);
        long firstExecution = System.currentTimeMillis();
        for(ActionWrapper.Result actionResult : watchRecord.execution().actionsResults()) {
            assertThat(actionResult.action().status(), equalTo(Action.Result.Status.SIMULATED));
        }
        ctx = getManualExecutionContext(new TimeValue(0, TimeUnit.SECONDS));
        watchRecord = executionService().execute(ctx);
        for(ActionWrapper.Result actionResult : watchRecord.execution().actionsResults()) {
            assertThat(actionResult.action().status(), equalTo(Action.Result.Status.THROTTLED));
        }

        if (timeWarped()) {
            timeWarp().clock().fastForwardSeconds(11);
        }

        assertBusy(new Runnable() {
            @Override
            public void run() {
                try {
                    ManualExecutionContext ctx = getManualExecutionContext(new TimeValue(0, TimeUnit.SECONDS));
                    WatchRecord watchRecord = executionService().execute(ctx);
                    for (ActionWrapper.Result actionResult : watchRecord.execution().actionsResults()) {
                        if ("ten_sec_throttle".equals(actionResult.id())) {
                            assertThat(actionResult.action().status(), equalTo(Action.Result.Status.SIMULATED));
                        } else {
                            assertThat(actionResult.action().status(), equalTo(Action.Result.Status.THROTTLED));
                        }
                    }
                } catch (IOException ioe) {
                    throw new ElasticsearchException("failed to execute", ioe);
                }
            }
        }, 11000 - (System.currentTimeMillis() - firstExecution), TimeUnit.MILLISECONDS);

    }

    @Test @Slow
    public void testDefaultThrottlePeriod() throws Exception {
        WatchSourceBuilder watchSourceBuilder = watchBuilder()
                .trigger(schedule(interval("60m")));

        AvailableAction availableAction = randomFrom(AvailableAction.values());
        final String actionType = availableAction.type();
        watchSourceBuilder.addAction("default_global_throttle", availableAction.action());

        PutWatchResponse putWatchResponse = watcherClient().putWatch(new PutWatchRequest("_id", watchSourceBuilder)).actionGet();
        assertThat(putWatchResponse.getVersion(), greaterThan(0L));
        refresh();

        if (timeWarped()) {
            timeWarp().clock().setTime(new DateTime(UTC));
        }

        ExecuteWatchResponse executeWatchResponse = watcherClient().prepareExecuteWatch("_id")
                .setTriggerEvent(new ManualTriggerEvent("execute_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC))))
                .setActionMode("default_global_throttle", ActionExecutionMode.SIMULATE)
                .setRecordExecution(true)
                .get();
        Map<String, Object> watchRecordMap = executeWatchResponse.getRecordSource().getAsMap();
        Object resultStatus = getExecutionStatus(actionType, watchRecordMap);
        assertThat(resultStatus.toString(), equalTo("simulated"));

        if (timeWarped()) {
            timeWarp().clock().fastForwardSeconds(1);
        }

        executeWatchResponse = watcherClient().prepareExecuteWatch("_id")
                .setTriggerEvent(new ManualTriggerEvent("execute_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC))))
                .setActionMode("default_global_throttle", ActionExecutionMode.SIMULATE)
                .setRecordExecution(true)
                .get();
        watchRecordMap = executeWatchResponse.getRecordSource().getAsMap();
        resultStatus = getExecutionStatus(actionType, watchRecordMap);
        assertThat(resultStatus.toString(), equalTo("throttled"));

        if (timeWarped()) {
            timeWarp().clock().fastForwardSeconds(5);
        }

        assertBusy(new Runnable() {
            @Override
            public void run() {
                try {
                    ExecuteWatchResponse executeWatchResponse = watcherClient().prepareExecuteWatch("_id")
                            .setTriggerEvent(new ManualTriggerEvent("execute_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC))))
                            .setActionMode("default_global_throttle", ActionExecutionMode.SIMULATE)
                            .setRecordExecution(true)
                            .get();
                    Map<String, Object> watchRecordMap = executeWatchResponse.getRecordSource().getAsMap();
                    Object resultStatus = getExecutionStatus(actionType, watchRecordMap);
                    assertThat(resultStatus.toString(), equalTo("simulated"));
                } catch (IOException ioe) {
                    throw new ElasticsearchException("failed to execute", ioe);
                }
            }
        }, 6, TimeUnit.SECONDS);
    }

    @Test @Slow
    public void testWatchThrottlePeriod() throws Exception {
        WatchSourceBuilder watchSourceBuilder = watchBuilder()
                .trigger(schedule(interval("60m")))
                .defaultThrottlePeriod(new TimeValue(1, TimeUnit.SECONDS));

        AvailableAction availableAction = randomFrom(AvailableAction.values());
        final String actionType = availableAction.type();
        watchSourceBuilder.addAction("default_global_throttle", availableAction.action());

        PutWatchResponse putWatchResponse = watcherClient().putWatch(new PutWatchRequest("_id", watchSourceBuilder)).actionGet();
        assertThat(putWatchResponse.getVersion(), greaterThan(0L));
        refresh();

        if (timeWarped()) {
            timeWarp().clock().setTime(new DateTime(UTC));
        }

        ExecuteWatchResponse executeWatchResponse = watcherClient().prepareExecuteWatch("_id")
                .setTriggerEvent(new ManualTriggerEvent("execute_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC))))
                .setActionMode("default_global_throttle", ActionExecutionMode.SIMULATE)
                .setRecordExecution(true)
                .get();
        Map<String, Object> watchRecordMap = executeWatchResponse.getRecordSource().getAsMap();
        Object resultStatus = getExecutionStatus(actionType, watchRecordMap);
        assertThat(resultStatus.toString(), equalTo("simulated"));

        if (timeWarped()) {
            timeWarp().clock().fastForwardSeconds(1);
        }

        executeWatchResponse = watcherClient().prepareExecuteWatch("_id")
                .setTriggerEvent(new ManualTriggerEvent("execute_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC))))
                .setActionMode("default_global_throttle", ActionExecutionMode.SIMULATE)
                .setRecordExecution(true)
                .get();
        watchRecordMap = executeWatchResponse.getRecordSource().getAsMap();
        resultStatus = getExecutionStatus(actionType, watchRecordMap);
        assertThat(resultStatus.toString(), equalTo("throttled"));

        if (timeWarped()) {
            timeWarp().clock().fastForwardSeconds(1);
        }
        assertBusy(new Runnable() {
            @Override
            public void run() {
                try {
                    //Since the default throttle period is 5 seconds but we have overridden the period in the watch this should trigger
                    ExecuteWatchResponse executeWatchResponse = watcherClient().prepareExecuteWatch("_id")
                            .setTriggerEvent(new ManualTriggerEvent("execute_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC))))
                            .setActionMode("default_global_throttle", ActionExecutionMode.SIMULATE)
                            .setRecordExecution(true)
                            .get();
                    Map<String, Object> watchRecordMap = executeWatchResponse.getRecordSource().getAsMap();
                    Object resultStatus = getExecutionStatus(actionType, watchRecordMap);
                    assertThat(resultStatus.toString(), equalTo("simulated"));
                } catch (IOException ioe) {
                    throw new ElasticsearchException("failed to execute", ioe);
                }
            }
        }, 1, TimeUnit.SECONDS);
    }

    @Test @Slow
    public void testFailingActionDoesGetThrottled() throws Exception {
        TimeValue throttlePeriod = new TimeValue(60, TimeUnit.MINUTES);
        WatchSourceBuilder watchSourceBuilder = watchBuilder()
                .trigger(new ScheduleTrigger(new IntervalSchedule(new IntervalSchedule.Interval(60, IntervalSchedule.Interval.Unit.MINUTES))))
                .defaultThrottlePeriod(throttlePeriod);
        watchSourceBuilder.addAction("logging", LoggingAction.builder(new Template.Builder.Inline("test out").build()));
        watchSourceBuilder.addAction("failing_hook", WebhookAction.builder(HttpRequestTemplate.builder("unknown.foo", 80).build()));

        PutWatchResponse putWatchResponse = watcherClient().putWatch(new PutWatchRequest("_id", watchSourceBuilder)).actionGet();
        assertThat(putWatchResponse.getVersion(), greaterThan(0L));
        refresh();

        ManualTriggerEvent triggerEvent = new ManualTriggerEvent("_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC)));
        ManualExecutionContext.Builder ctxBuilder = ManualExecutionContext.builder(watchService().getWatch("_id"), triggerEvent, throttlePeriod);
        ctxBuilder.recordExecution(true);

        ManualExecutionContext ctx = ctxBuilder.build();
        WatchRecord watchRecord = executionService().execute(ctx);

        assertThat(watchRecord.execution().actionsResults().get("logging").action().status(), equalTo(Action.Result.Status.SUCCESS));
        assertThat(watchRecord.execution().actionsResults().get("failing_hook").action().status(), equalTo(Action.Result.Status.FAILURE));
        assertThat(watchRecord.state(), equalTo(ExecutionState.EXECUTED));

        triggerEvent = new ManualTriggerEvent("_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC)));
        ctxBuilder = ManualExecutionContext.builder(watchService().getWatch("_id"), triggerEvent, throttlePeriod);
        ctxBuilder.recordExecution(true);

        ctx = ctxBuilder.build();
        watchRecord = executionService().execute(ctx);
        assertThat(watchRecord.execution().actionsResults().get("logging").action().status(), equalTo(Action.Result.Status.THROTTLED));
        assertThat(watchRecord.execution().actionsResults().get("failing_hook").action().status(), equalTo(Action.Result.Status.FAILURE));
        assertThat(watchRecord.state(), equalTo(ExecutionState.THROTTLED));
    }


    private String getExecutionStatus(String actionType, Map<String, Object> watchRecordMap) {
        Object actionResults = XContentMapValues.extractValue(WatchRecord.Field.EXECUTION_RESULT.getPreferredName() + "."
                + WatchExecutionResult.Field.ACTIONS.getPreferredName(), watchRecordMap);

        assertThat(actionResults, instanceOf(List.class));

        List actionResultsList = (List) actionResults;
        assertThat(actionResultsList.get(0), instanceOf(Map.class));
        return XContentMapValues.extractValue(actionType + ".status",  (Map<String,Object>) actionResultsList.get(0)).toString();
    }

    private ManualExecutionContext getManualExecutionContext(TimeValue throttlePeriod) {
        ManualTriggerEvent triggerEvent = new ManualTriggerEvent("_id", new ScheduleTriggerEvent(new DateTime(UTC), new DateTime(UTC)));
        return ManualExecutionContext.builder(watchService().getWatch("_id"), triggerEvent, throttlePeriod)
                .executionTime(timeWarped() ? timeWarp().clock().nowUTC() : SystemClock.INSTANCE.nowUTC())
                .allActionsMode(ActionExecutionMode.SIMULATE)
                .recordExecution(true)
                .build();
    }

    enum AvailableAction {
        EMAIL {
            @Override
            public Action.Builder action() throws Exception {
                EmailTemplate.Builder emailBuilder = EmailTemplate.builder();
                emailBuilder.from("test@test.com");
                emailBuilder.to("test@test.com");
                emailBuilder.subject("test subject");
                return EmailAction.builder(emailBuilder.build());
            }

            @Override
            public String type() {
                return EmailAction.TYPE;
            }
        },
        WEBHOOK {
            @Override
            public Action.Builder action() throws Exception {
                HttpRequestTemplate.Builder requestBuilder = HttpRequestTemplate.builder("foo.bar.com", 1234);
                return WebhookAction.builder(requestBuilder.build());
            }

            @Override
            public String type() {
                return WebhookAction.TYPE;
            }
        },
        LOGGING {
            @Override
            public Action.Builder action() throws Exception {
                Template.Builder templateBuilder = new Template.Builder.Inline("{{ctx.watch_id}}");
                return LoggingAction.builder(templateBuilder.build());
            }

            @Override
            public String type() {
                return LoggingAction.TYPE;
            }
        },
        INDEX {
            @Override
            public Action.Builder action() throws Exception {
                return IndexAction.builder("test_index", "test_type");
            }

            @Override
            public String type() {
                return IndexAction.TYPE;
            }
        };


        public abstract Action.Builder action() throws Exception;

        public abstract String type();
    }

}
