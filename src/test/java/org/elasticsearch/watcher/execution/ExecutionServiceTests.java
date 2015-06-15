/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.execution;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.watcher.actions.*;
import org.elasticsearch.watcher.actions.throttler.ActionThrottler;
import org.elasticsearch.watcher.actions.throttler.Throttler;
import org.elasticsearch.watcher.condition.Condition;
import org.elasticsearch.watcher.condition.ExecutableCondition;
import org.elasticsearch.watcher.condition.always.AlwaysCondition;
import org.elasticsearch.watcher.condition.never.NeverCondition;
import org.elasticsearch.watcher.history.HistoryStore;
import org.elasticsearch.watcher.history.WatchRecord;
import org.elasticsearch.watcher.input.ExecutableInput;
import org.elasticsearch.watcher.input.Input;
import org.elasticsearch.watcher.support.clock.Clock;
import org.elasticsearch.watcher.support.clock.ClockMock;
import org.elasticsearch.watcher.support.validation.WatcherSettingsValidation;
import org.elasticsearch.watcher.transform.ExecutableTransform;
import org.elasticsearch.watcher.transform.Transform;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.watcher.watch.*;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.hamcrest.Matchers.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

/**
 */
public class ExecutionServiceTests extends ElasticsearchTestCase {

    private Payload payload;
    private ExecutableInput input;
    private Input.Result inputResult;

    private WatchStore watchStore;
    private TriggeredWatchStore triggeredWatchStore;
    private HistoryStore historyStore;
    private WatchLockService watchLockService;
    private ExecutionService executionService;
    private Clock clock;

    @Before
    public void init() throws Exception {
        payload = mock(Payload.class);
        input = mock(ExecutableInput.class);
        inputResult = mock(Input.Result.class);
        when(inputResult.status()).thenReturn(Input.Result.Status.SUCCESS);
        when(inputResult.payload()).thenReturn(payload);
        when(input.execute(any(WatchExecutionContext.class))).thenReturn(inputResult);

        watchStore = mock(WatchStore.class);
        triggeredWatchStore = mock(TriggeredWatchStore.class);
        historyStore = mock(HistoryStore.class);

        WatchExecutor executor = mock(WatchExecutor.class);
        when(executor.queue()).thenReturn(new ArrayBlockingQueue<Runnable>(1));

        watchLockService = mock(WatchLockService.class);
        WatcherSettingsValidation settingsValidator = mock(WatcherSettingsValidation.class);
        clock = new ClockMock();
        executionService = new ExecutionService(ImmutableSettings.EMPTY, historyStore, triggeredWatchStore, executor, watchStore, watchLockService, clock, settingsValidator);

        ClusterState clusterState = mock(ClusterState.class);
        when(triggeredWatchStore.loadTriggeredWatches(clusterState)).thenReturn(ImmutableList.<TriggeredWatch>of());
        executionService.start(clusterState);
    }

    @Test
    public void testExecute() throws Exception {
        WatchLockService.Lock lock = mock(WatchLockService.Lock.class);
        when(watchLockService.acquire("_id")).thenReturn(lock);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        when(watchStore.get("_id")).thenReturn(watch);

        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", clock.nowUTC(), clock.nowUTC());
        WatchExecutionContext context = new TriggeredExecutionContext(watch, clock.nowUTC(), event, timeValueSeconds(5));

        Condition.Result conditionResult = AlwaysCondition.Result.INSTANCE;
        ExecutableCondition condition = mock(ExecutableCondition.class);
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        // watch level transform
        Transform.Result watchTransformResult = mock(Transform.Result.class);
        when(watchTransformResult.status()).thenReturn(Transform.Result.Status.SUCCESS);
        when(watchTransformResult.payload()).thenReturn(payload);
        ExecutableTransform watchTransform = mock(ExecutableTransform.class);
        when(watchTransform.execute(context, payload)).thenReturn(watchTransformResult);

        // action throttler
        Throttler.Result throttleResult = mock(Throttler.Result.class);
        when(throttleResult.throttle()).thenReturn(false);
        ActionThrottler throttler = mock(ActionThrottler.class);
        when(throttler.throttle("_action", context)).thenReturn(throttleResult);

        // action level transform
        Transform.Result actionTransformResult = mock(Transform.Result.class);
        when(actionTransformResult.payload()).thenReturn(payload);
        ExecutableTransform actionTransform = mock(ExecutableTransform.class);
        when(actionTransform.execute(context, payload)).thenReturn(actionTransformResult);

        // the action
        Action.Result actionResult = mock(Action.Result.class);
        when(actionResult.type()).thenReturn("_action_type");
        when(actionResult.status()).thenReturn(Action.Result.Status.SUCCESS);
        ExecutableAction action = mock(ExecutableAction.class);
        when(action.execute("_action", context, payload)).thenReturn(actionResult);

        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, actionTransform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(clock.nowUTC())));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.transform()).thenReturn(watchTransform);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.execute(context);
        assertThat(watchRecord.result().conditionResult(), sameInstance(conditionResult));
        assertThat(watchRecord.result().transformResult(), sameInstance(watchTransformResult));
        ActionWrapper.Result result = watchRecord.result().actionsResults().get("_action");
        assertThat(result, notNullValue());
        assertThat(result.id(), is("_action"));
        assertThat(result.transform(), sameInstance(actionTransformResult));
        assertThat(result.action(), sameInstance(actionResult));

        verify(historyStore, times(1)).put(watchRecord);
        verify(lock, times(1)).release();
        verify(condition, times(1)).execute(context);
        verify(watchTransform, times(1)).execute(context, payload);
        verify(action, times(1)).execute("_action", context, payload);
    }

    @Test
    public void testExecute_FailedInput() throws Exception {
        WatchLockService.Lock lock = mock(WatchLockService.Lock.class);
        when(watchLockService.acquire("_id")).thenReturn(lock);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        when(watchStore.get("_id")).thenReturn(watch);

        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", clock.nowUTC(), clock.nowUTC());
        WatchExecutionContext context = new TriggeredExecutionContext(watch, clock.nowUTC(), event, timeValueSeconds(5));

        input = mock(ExecutableInput.class);
        Input.Result inputResult = mock(Input.Result.class);
        when(inputResult.status()).thenReturn(Input.Result.Status.FAILURE);
        when(inputResult.reason()).thenReturn("_reason");
        when(input.execute(context)).thenReturn(inputResult);

        Condition.Result conditionResult = AlwaysCondition.Result.INSTANCE;
        ExecutableCondition condition = mock(ExecutableCondition.class);
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        // watch level transform
        Transform.Result watchTransformResult = mock(Transform.Result.class);
        when(watchTransformResult.payload()).thenReturn(payload);
        ExecutableTransform watchTransform = mock(ExecutableTransform.class);
        when(watchTransform.execute(context, payload)).thenReturn(watchTransformResult);

        // action throttler
        Throttler.Result throttleResult = mock(Throttler.Result.class);
        when(throttleResult.throttle()).thenReturn(false);
        ActionThrottler throttler = mock(ActionThrottler.class);
        when(throttler.throttle("_action", context)).thenReturn(throttleResult);

        // action level transform
        Transform.Result actionTransformResult = mock(Transform.Result.class);
        when(actionTransformResult.payload()).thenReturn(payload);
        ExecutableTransform actionTransform = mock(ExecutableTransform.class);
        when(actionTransform.execute(context, payload)).thenReturn(actionTransformResult);

        // the action
        Action.Result actionResult = mock(Action.Result.class);
        when(actionResult.type()).thenReturn("_action_type");
        when(actionResult.status()).thenReturn(Action.Result.Status.SUCCESS);
        ExecutableAction action = mock(ExecutableAction.class);
        when(action.execute("_action", context, payload)).thenReturn(actionResult);

        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, actionTransform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(clock.nowUTC())));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.transform()).thenReturn(watchTransform);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.execute(context);
        assertThat(watchRecord.result().inputResult(), is(inputResult));
        assertThat(watchRecord.result().conditionResult(), nullValue());
        assertThat(watchRecord.result().transformResult(), nullValue());
        assertThat(watchRecord.result().actionsResults(), notNullValue());
        assertThat(watchRecord.result().actionsResults().count(), is(0));

        verify(historyStore, times(1)).put(watchRecord);
        verify(lock, times(1)).release();
        verify(input, times(1)).execute(context);
        verify(condition, never()).execute(context);
        verify(watchTransform, never()).execute(context, payload);
        verify(action, never()).execute("_action", context, payload);
    }

    @Test
    public void testExecute_FailedCondition() throws Exception {
        WatchLockService.Lock lock = mock(WatchLockService.Lock.class);
        when(watchLockService.acquire("_id")).thenReturn(lock);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        when(watchStore.get("_id")).thenReturn(watch);

        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", clock.nowUTC(), clock.nowUTC());
        WatchExecutionContext context = new TriggeredExecutionContext(watch, clock.nowUTC(), event, timeValueSeconds(5));

        ExecutableCondition condition = mock(ExecutableCondition.class);
        Condition.Result conditionResult = mock(Condition.Result.class);
        when(conditionResult.status()).thenReturn(Condition.Result.Status.FAILURE);
        when(conditionResult.reason()).thenReturn("_reason");
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        // watch level transform
        Transform.Result watchTransformResult = mock(Transform.Result.class);
        when(watchTransformResult.payload()).thenReturn(payload);
        ExecutableTransform watchTransform = mock(ExecutableTransform.class);
        when(watchTransform.execute(context, payload)).thenReturn(watchTransformResult);

        // action throttler
        Throttler.Result throttleResult = mock(Throttler.Result.class);
        when(throttleResult.throttle()).thenReturn(false);
        ActionThrottler throttler = mock(ActionThrottler.class);
        when(throttler.throttle("_action", context)).thenReturn(throttleResult);

        // action level transform
        Transform.Result actionTransformResult = mock(Transform.Result.class);
        when(actionTransformResult.payload()).thenReturn(payload);
        ExecutableTransform actionTransform = mock(ExecutableTransform.class);
        when(actionTransform.execute(context, payload)).thenReturn(actionTransformResult);

        // the action
        Action.Result actionResult = mock(Action.Result.class);
        when(actionResult.type()).thenReturn("_action_type");
        when(actionResult.status()).thenReturn(Action.Result.Status.SUCCESS);
        ExecutableAction action = mock(ExecutableAction.class);
        when(action.execute("_action", context, payload)).thenReturn(actionResult);

        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, actionTransform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(clock.nowUTC())));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.transform()).thenReturn(watchTransform);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.execute(context);
        assertThat(watchRecord.result().inputResult(), is(inputResult));
        assertThat(watchRecord.result().conditionResult(), is(conditionResult));
        assertThat(watchRecord.result().transformResult(), nullValue());
        assertThat(watchRecord.result().actionsResults(), notNullValue());
        assertThat(watchRecord.result().actionsResults().count(), is(0));

        verify(historyStore, times(1)).put(watchRecord);
        verify(lock, times(1)).release();
        verify(input, times(1)).execute(context);
        verify(condition, times(1)).execute(context);
        verify(watchTransform, never()).execute(context, payload);
        verify(action, never()).execute("_action", context, payload);
    }

    @Test
    public void testExecute_FailedWatchTransform() throws Exception {
        WatchLockService.Lock lock = mock(WatchLockService.Lock.class);
        when(watchLockService.acquire("_id")).thenReturn(lock);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        when(watchStore.get("_id")).thenReturn(watch);

        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", clock.nowUTC(), clock.nowUTC());
        WatchExecutionContext context = new TriggeredExecutionContext(watch, clock.nowUTC(), event, timeValueSeconds(5));

        Condition.Result conditionResult = AlwaysCondition.Result.INSTANCE;
        ExecutableCondition condition = mock(ExecutableCondition.class);
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        // watch level transform
        Transform.Result watchTransformResult = mock(Transform.Result.class);
        when(watchTransformResult.status()).thenReturn(Transform.Result.Status.FAILURE);
        when(watchTransformResult.reason()).thenReturn("_reason");
        ExecutableTransform watchTransform = mock(ExecutableTransform.class);
        when(watchTransform.execute(context, payload)).thenReturn(watchTransformResult);

        // action throttler
        Throttler.Result throttleResult = mock(Throttler.Result.class);
        when(throttleResult.throttle()).thenReturn(false);
        ActionThrottler throttler = mock(ActionThrottler.class);
        when(throttler.throttle("_action", context)).thenReturn(throttleResult);

        // action level transform
        Transform.Result actionTransformResult = mock(Transform.Result.class);
        when(actionTransformResult.payload()).thenReturn(payload);
        ExecutableTransform actionTransform = mock(ExecutableTransform.class);
        when(actionTransform.execute(context, payload)).thenReturn(actionTransformResult);

        // the action
        Action.Result actionResult = mock(Action.Result.class);
        when(actionResult.type()).thenReturn("_action_type");
        when(actionResult.status()).thenReturn(Action.Result.Status.SUCCESS);
        ExecutableAction action = mock(ExecutableAction.class);
        when(action.execute("_action", context, payload)).thenReturn(actionResult);

        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, actionTransform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(clock.nowUTC())));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.transform()).thenReturn(watchTransform);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.execute(context);
        assertThat(watchRecord.result().inputResult(), is(inputResult));
        assertThat(watchRecord.result().conditionResult(), is(conditionResult));
        assertThat(watchRecord.result().transformResult(), is(watchTransformResult));
        assertThat(watchRecord.result().actionsResults(), notNullValue());
        assertThat(watchRecord.result().actionsResults().count(), is(0));

        verify(historyStore, times(1)).put(watchRecord);
        verify(lock, times(1)).release();
        verify(input, times(1)).execute(context);
        verify(condition, times(1)).execute(context);
        verify(watchTransform, times(1)).execute(context, payload);
        verify(action, never()).execute("_action", context, payload);
    }

    @Test
    public void testExecute_FailedActionTransform() throws Exception {
        WatchLockService.Lock lock = mock(WatchLockService.Lock.class);
        when(watchLockService.acquire("_id")).thenReturn(lock);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        when(watchStore.get("_id")).thenReturn(watch);

        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", clock.nowUTC(), clock.nowUTC());
        WatchExecutionContext context = new TriggeredExecutionContext(watch, clock.nowUTC(), event, timeValueSeconds(5));

        Condition.Result conditionResult = AlwaysCondition.Result.INSTANCE;
        ExecutableCondition condition = mock(ExecutableCondition.class);
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        // watch level transform
        Transform.Result watchTransformResult = mock(Transform.Result.class);
        when(watchTransformResult.status()).thenReturn(Transform.Result.Status.SUCCESS);
        when(watchTransformResult.payload()).thenReturn(payload);
        ExecutableTransform watchTransform = mock(ExecutableTransform.class);
        when(watchTransform.execute(context, payload)).thenReturn(watchTransformResult);

        // action throttler
        Throttler.Result throttleResult = mock(Throttler.Result.class);
        when(throttleResult.throttle()).thenReturn(false);
        ActionThrottler throttler = mock(ActionThrottler.class);
        when(throttler.throttle("_action", context)).thenReturn(throttleResult);

        // action level transform
        Transform.Result actionTransformResult = mock(Transform.Result.class);
        when(actionTransformResult.status()).thenReturn(Transform.Result.Status.FAILURE);
        when(actionTransformResult.reason()).thenReturn("_reason");
        ExecutableTransform actionTransform = mock(ExecutableTransform.class);
        when(actionTransform.execute(context, payload)).thenReturn(actionTransformResult);

        // the action
        Action.Result actionResult = mock(Action.Result.class);
        when(actionResult.type()).thenReturn("_action_type");
        when(actionResult.status()).thenReturn(Action.Result.Status.SUCCESS);
        ExecutableAction action = mock(ExecutableAction.class);
        when(action.logger()).thenReturn(logger);
        when(action.execute("_action", context, payload)).thenReturn(actionResult);

        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, actionTransform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(clock.nowUTC())));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.transform()).thenReturn(watchTransform);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.execute(context);
        assertThat(watchRecord.result().inputResult(), is(inputResult));
        assertThat(watchRecord.result().conditionResult(), is(conditionResult));
        assertThat(watchRecord.result().transformResult(), is(watchTransformResult));
        assertThat(watchRecord.result().actionsResults(), notNullValue());
        assertThat(watchRecord.result().actionsResults().count(), is(1));
        assertThat(watchRecord.result().actionsResults().get("_action").transform(), is(actionTransformResult));
        assertThat(watchRecord.result().actionsResults().get("_action").action().status(), is(Action.Result.Status.FAILURE));

        verify(historyStore, times(1)).put(watchRecord);
        verify(lock, times(1)).release();
        verify(input, times(1)).execute(context);
        verify(condition, times(1)).execute(context);
        verify(watchTransform, times(1)).execute(context, payload);
        // the action level transform is executed before the action itself
        verify(action, never()).execute("_action", context, payload);
    }

    @Test
    public void testExecuteInner() throws Exception {
        DateTime now = DateTime.now(UTC);
        Watch watch = mock(Watch.class);
        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", now, now);
        WatchExecutionContext context = new TriggeredExecutionContext(watch, now, event, timeValueSeconds(5));

        Condition.Result conditionResult = AlwaysCondition.Result.INSTANCE;
        ExecutableCondition condition = mock(ExecutableCondition.class);
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        // watch level transform
        Transform.Result watchTransformResult = mock(Transform.Result.class);
        when(watchTransformResult.payload()).thenReturn(payload);
        ExecutableTransform watchTransform = mock(ExecutableTransform.class);
        when(watchTransform.execute(context, payload)).thenReturn(watchTransformResult);

        // action throttler
        Throttler.Result throttleResult = mock(Throttler.Result.class);
        when(throttleResult.throttle()).thenReturn(false);
        ActionThrottler throttler = mock(ActionThrottler.class);
        when(throttler.throttle("_action", context)).thenReturn(throttleResult);

        // action level transform
        Transform.Result actionTransformResult = mock(Transform.Result.class);
        when(actionTransformResult.payload()).thenReturn(payload);
        ExecutableTransform actionTransform = mock(ExecutableTransform.class);
        when(actionTransform.execute(context, payload)).thenReturn(actionTransformResult);

        // the action
        Action.Result actionResult = mock(Action.Result.class);
        when(actionResult.type()).thenReturn("_action_type");
        when(actionResult.status()).thenReturn(Action.Result.Status.SUCCESS);
        ExecutableAction action = mock(ExecutableAction.class);
        when(action.execute("_action", context, payload)).thenReturn(actionResult);

        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, actionTransform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(now)));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.transform()).thenReturn(watchTransform);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.executeInner(context);
        assertThat(watchRecord.result().conditionResult(), sameInstance(conditionResult));
        assertThat(watchRecord.result().transformResult(), sameInstance(watchTransformResult));
        ActionWrapper.Result result = watchRecord.result().actionsResults().get("_action");
        assertThat(result, notNullValue());
        assertThat(result.id(), is("_action"));
        assertThat(result.transform(), sameInstance(actionTransformResult));
        assertThat(result.action(), sameInstance(actionResult));

        verify(condition, times(1)).execute(context);
        verify(watchTransform, times(1)).execute(context, payload);
        verify(action, times(1)).execute("_action", context, payload);
    }

    @Test
    public void testExecuteInner_throttled() throws Exception {
        DateTime now = DateTime.now(UTC);
        Watch watch = mock(Watch.class);
        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", now, now);
        WatchExecutionContext context = new TriggeredExecutionContext(watch, now, event, timeValueSeconds(5));

        Condition.Result conditionResult = AlwaysCondition.Result.INSTANCE;
        ExecutableCondition condition = mock(ExecutableCondition.class);
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        Throttler.Result throttleResult = mock(Throttler.Result.class);
        when(throttleResult.throttle()).thenReturn(true);
        when(throttleResult.reason()).thenReturn("_throttle_reason");
        ActionThrottler throttler = mock(ActionThrottler.class);
        when(throttler.throttle("_action", context)).thenReturn(throttleResult);

        ExecutableTransform transform = mock(ExecutableTransform.class);

        ExecutableAction action = mock(ExecutableAction.class);
        when(action.type()).thenReturn("_type");
        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, transform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(now)));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.executeInner(context);
        assertThat(watchRecord.result().inputResult(), sameInstance(inputResult));
        assertThat(watchRecord.result().conditionResult(), sameInstance(conditionResult));
        assertThat(watchRecord.result().transformResult(), nullValue());
        assertThat(watchRecord.result().actionsResults().count(), is(1));
        ActionWrapper.Result result = watchRecord.result().actionsResults().get("_action");
        assertThat(result, notNullValue());
        assertThat(result.id(), is("_action"));
        assertThat(result.transform(), nullValue());
        assertThat(result.action(), instanceOf(Action.Result.Throttled.class));
        Action.Result.Throttled throttled = (Action.Result.Throttled) result.action();
        assertThat(throttled.reason(), is("_throttle_reason"));

        verify(condition, times(1)).execute(context);
        verify(throttler, times(1)).throttle("_action", context);
        verify(transform, never()).execute(context, payload);
    }

    @Test
    public void testExecuteInner_conditionNotMet() throws Exception {
        DateTime now = DateTime.now(UTC);
        Watch watch = mock(Watch.class);
        ScheduleTriggerEvent event = new ScheduleTriggerEvent("_id", now, now);
        WatchExecutionContext context = new TriggeredExecutionContext(watch, now, event, timeValueSeconds(5));

        Condition.Result conditionResult = NeverCondition.Result.INSTANCE;
        ExecutableCondition condition = mock(ExecutableCondition.class);
        when(condition.execute(any(WatchExecutionContext.class))).thenReturn(conditionResult);

        // watch level transform
        ExecutableTransform watchTransform = mock(ExecutableTransform.class);

        // action throttler
        ActionThrottler throttler = mock(ActionThrottler.class);
        ExecutableTransform actionTransform = mock(ExecutableTransform.class);
        ExecutableAction action = mock(ExecutableAction.class);
        ActionWrapper actionWrapper = new ActionWrapper("_action", throttler, actionTransform, action);
        ExecutableActions actions = new ExecutableActions(Arrays.asList(actionWrapper));

        WatchStatus watchStatus = new WatchStatus(ImmutableMap.of("_action", new ActionStatus(now)));

        when(watch.input()).thenReturn(input);
        when(watch.condition()).thenReturn(condition);
        when(watch.transform()).thenReturn(watchTransform);
        when(watch.actions()).thenReturn(actions);
        when(watch.status()).thenReturn(watchStatus);

        WatchRecord watchRecord = executionService.executeInner(context);
        assertThat(watchRecord.result().inputResult(), sameInstance(inputResult));
        assertThat(watchRecord.result().conditionResult(), sameInstance(conditionResult));
        assertThat(watchRecord.result().transformResult(), nullValue());
        assertThat(watchRecord.result().actionsResults().count(), is(0));

        verify(condition, times(1)).execute(context);
        verify(watchTransform, never()).execute(context, payload);
        verify(throttler, never()).throttle("_action", context);
        verify(actionTransform, never()).execute(context, payload);
        verify(action, never()).execute("_action", context, payload);
    }

}
