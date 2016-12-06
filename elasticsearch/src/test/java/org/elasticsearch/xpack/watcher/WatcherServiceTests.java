/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher;

import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.support.clock.ClockMock;
import org.elasticsearch.xpack.watcher.execution.ExecutionService;
import org.elasticsearch.xpack.watcher.support.WatcherIndexTemplateRegistry;
import org.elasticsearch.xpack.watcher.trigger.Trigger;
import org.elasticsearch.xpack.watcher.trigger.TriggerEngine;
import org.elasticsearch.xpack.watcher.trigger.TriggerService;
import org.elasticsearch.xpack.watcher.watch.Watch;
import org.elasticsearch.xpack.watcher.watch.WatchLockService;
import org.elasticsearch.xpack.watcher.watch.WatchStatus;
import org.elasticsearch.xpack.watcher.watch.WatchStore;
import org.joda.time.DateTime;
import org.junit.Before;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.joda.time.DateTimeZone.UTC;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class WatcherServiceTests extends ESTestCase {
    private TriggerService triggerService;
    private WatchStore watchStore;
    private Watch.Parser watchParser;
    private WatcherService watcherService;
    private ClockMock clock;

    @Before
    public void init() throws Exception {
        triggerService = mock(TriggerService.class);
        watchStore = mock(WatchStore.class);
        watchParser = mock(Watch.Parser.class);
        ExecutionService executionService = mock(ExecutionService.class);
        WatchLockService watchLockService = mock(WatchLockService.class);
        clock = ClockMock.frozen();
        WatcherIndexTemplateRegistry watcherIndexTemplateRegistry = mock(WatcherIndexTemplateRegistry.class);
        watcherService = new WatcherService(Settings.EMPTY, clock, triggerService, watchStore, watchParser, executionService,
                watchLockService, watcherIndexTemplateRegistry);
        AtomicReference<WatcherState> state = watcherService.state;
        state.set(WatcherState.STARTED);
    }

    public void testPutWatch() throws Exception {
        boolean activeByDefault = randomBoolean();

        IndexResponse indexResponse = mock(IndexResponse.class);
        Watch newWatch = mock(Watch.class);
        WatchStatus status = mock(WatchStatus.class);
        when(status.state()).thenReturn(new WatchStatus.State(activeByDefault, new DateTime(clock.millis(), UTC)));
        when(newWatch.status()).thenReturn(status);

        WatchStore.WatchPut watchPut = mock(WatchStore.WatchPut.class);
        when(watchPut.indexResponse()).thenReturn(indexResponse);
        when(watchPut.current()).thenReturn(newWatch);

        when(watchParser.parseWithSecrets(any(String.class), eq(false), any(BytesReference.class), any(DateTime.class)))
                .thenReturn(newWatch);
        when(watchStore.put(newWatch)).thenReturn(watchPut);
        IndexResponse response = watcherService.putWatch("_id", new BytesArray("{}"), activeByDefault);
        assertThat(response, sameInstance(indexResponse));

        verify(newWatch, times(1)).setState(activeByDefault, new DateTime(clock.millis(), UTC));
        if (activeByDefault) {
            verify(triggerService, times(1)).add(any(TriggerEngine.Job.class));
        } else {
            verifyZeroInteractions(triggerService);
        }
    }

    public void testPutWatchDifferentActiveStates() throws Exception {
        Trigger trigger = mock(Trigger.class);

        IndexResponse indexResponse = mock(IndexResponse.class);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        WatchStatus status = mock(WatchStatus.class);
        boolean active = randomBoolean();
        DateTime now = new DateTime(clock.millis(), UTC);
        when(status.state()).thenReturn(new WatchStatus.State(active, now));
        when(watch.status()).thenReturn(status);
        when(watch.trigger()).thenReturn(trigger);
        WatchStore.WatchPut watchPut = mock(WatchStore.WatchPut.class);
        when(watchPut.indexResponse()).thenReturn(indexResponse);
        when(watchPut.current()).thenReturn(watch);

        Watch previousWatch = mock(Watch.class);
        WatchStatus previousStatus = mock(WatchStatus.class);
        boolean prevActive = randomBoolean();
        when(previousStatus.state()).thenReturn(new WatchStatus.State(prevActive, now));
        when(previousWatch.status()).thenReturn(previousStatus);
        when(previousWatch.trigger()).thenReturn(trigger);
        when(watchPut.previous()).thenReturn(previousWatch);

        when(watchParser.parseWithSecrets(any(String.class), eq(false), any(BytesReference.class), eq(now))).thenReturn(watch);
        when(watchStore.put(watch)).thenReturn(watchPut);

        IndexResponse response = watcherService.putWatch("_id", new BytesArray("{}"), active);
        assertThat(response, sameInstance(indexResponse));

        if (!active) {
            // we should always remove the watch from the trigger service, just to be safe
            verify(triggerService, times(1)).remove("_id");
        } else if (prevActive) {
            // if both the new watch and the prev one are active, we should do nothing
            verifyZeroInteractions(triggerService);
        } else {
            // if the prev watch was not active and the new one is active, we should add the watch
            verify(triggerService, times(1)).add(watch);
        }
    }

    public void testDeleteWatch() throws Exception {
        WatchStore.WatchDelete expectedWatchDelete = mock(WatchStore.WatchDelete.class);
        DeleteResponse deleteResponse = mock(DeleteResponse.class);
        when(deleteResponse.getResult()).thenReturn(DocWriteResponse.Result.DELETED);
        when(expectedWatchDelete.deleteResponse()).thenReturn(deleteResponse);
        when(watchStore.delete("_id")).thenReturn(expectedWatchDelete);
        WatchStore.WatchDelete watchDelete = watcherService.deleteWatch("_id");

        assertThat(watchDelete, sameInstance(expectedWatchDelete));
        verify(triggerService, times(1)).remove("_id");
    }

    public void testDeleteWatchNotFound() throws Exception {
        WatchStore.WatchDelete expectedWatchDelete = mock(WatchStore.WatchDelete.class);
        DeleteResponse deleteResponse = mock(DeleteResponse.class);
        when(deleteResponse.getResult()).thenReturn(DocWriteResponse.Result.NOOP);
        when(expectedWatchDelete.deleteResponse()).thenReturn(deleteResponse);
        when(watchStore.delete("_id")).thenReturn(expectedWatchDelete);
        WatchStore.WatchDelete watchDelete = watcherService.deleteWatch("_id");

        assertThat(watchDelete, sameInstance(expectedWatchDelete));
        verifyZeroInteractions(triggerService);
    }

    public void testAckWatch() throws Exception {
        DateTime now = new DateTime(UTC);
        clock.setTime(now);
        Watch watch = mock(Watch.class);
        when(watch.ack(now, "_all")).thenReturn(true);
        WatchStatus status = new WatchStatus(now, emptyMap());
        when(watch.status()).thenReturn(status);
        when(watchStore.get("_id")).thenReturn(watch);

        WatchStatus result = watcherService.ackWatch("_id", Strings.EMPTY_ARRAY);
        assertThat(result, not(sameInstance(status)));

        verify(watchStore, times(1)).updateStatus(watch);
    }

    public void testActivate() throws Exception {
        WatcherService service = spy(watcherService);
        WatchStatus expectedStatus = mock(WatchStatus.class);
        doReturn(expectedStatus).when(service).setWatchState("_id", true);
        WatchStatus actualStatus = service.activateWatch("_id");
        assertThat(actualStatus, sameInstance(expectedStatus));
        verify(service, times(1)).setWatchState("_id", true);
    }

    public void testDeactivate() throws Exception {
        WatcherService service = spy(watcherService);
        WatchStatus expectedStatus = mock(WatchStatus.class);
        doReturn(expectedStatus).when(service).setWatchState("_id", false);
        WatchStatus actualStatus = service.deactivateWatch("_id");
        assertThat(actualStatus, sameInstance(expectedStatus));
        verify(service, times(1)).setWatchState("_id", false);
    }

    public void testSetWatchStateSetActiveOnCurrentlyActive() throws Exception {
        // trying to activate a watch that is already active:
        //  - the watch status should not change
        //  - the watch doesn't need to be updated in the store
        //  - the watch should not be removed or re-added to the trigger service
        DateTime now = new DateTime(UTC);
        clock.setTime(now);

        Watch watch = mock(Watch.class);
        WatchStatus status = new WatchStatus(now, emptyMap());
        when(watch.status()).thenReturn(status);
        when(watch.setState(true, now)).thenReturn(false);

        when(watchStore.get("_id")).thenReturn(watch);


        WatchStatus result = watcherService.setWatchState("_id", true);
        assertThat(result, not(sameInstance(status)));

        verifyZeroInteractions(triggerService);
        verify(watchStore, never()).updateStatus(watch);
    }

    public void testSetWatchStateSetActiveOnCurrentlyInactive() throws Exception {
        // activating a watch that is currently inactive:
        //  - the watch status should be updated
        //  - the watch needs to be updated in the store
        //  - the watch should be re-added to the trigger service (the assumption is that it's not there)

        DateTime now = new DateTime(UTC);
        clock.setTime(now);

        Watch watch = mock(Watch.class);
        WatchStatus status = new WatchStatus(now, emptyMap());
        when(watch.status()).thenReturn(status);
        when(watch.setState(true, now)).thenReturn(true);

        when(watchStore.get("_id")).thenReturn(watch);

        WatchStatus result = watcherService.setWatchState("_id", true);
        assertThat(result, not(sameInstance(status)));

        verify(triggerService, times(1)).add(watch);
        verify(watchStore, times(1)).updateStatus(watch);
    }

    public void testSetWatchStateSetInactiveOnCurrentlyActive() throws Exception {
        // deactivating a watch that is currently active:
        //  - the watch status should change
        //  - the watch needs to be updated in the store
        //  - the watch should be removed from the trigger service
        DateTime now = new DateTime(UTC);
        clock.setTime(now);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        WatchStatus status = new WatchStatus(now, emptyMap());
        when(watch.status()).thenReturn(status);
        when(watch.setState(false, now)).thenReturn(true);

        when(watchStore.get("_id")).thenReturn(watch);

        WatchStatus result = watcherService.setWatchState("_id", false);
        assertThat(result, not(sameInstance(status)));

        verify(triggerService, times(1)).remove("_id");
        verify(watchStore, times(1)).updateStatus(watch);
    }

    public void testSetWatchStateSetInactiveOnCurrentlyInactive() throws Exception {
        // trying to deactivate a watch that is currently inactive:
        //  - the watch status should not be updated
        //  - the watch should not be updated in the store
        //  - the watch should be re-added or removed to/from the trigger service
        DateTime now = new DateTime(UTC);
        clock.setTime(now);

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn("_id");
        WatchStatus status = new WatchStatus(now, emptyMap());
        when(watch.status()).thenReturn(status);
        when(watch.setState(false, now)).thenReturn(false);

        when(watchStore.get("_id")).thenReturn(watch);

        WatchStatus result = watcherService.setWatchState("_id", false);
        assertThat(result, not(sameInstance(status)));

        verifyZeroInteractions(triggerService);
        verify(watchStore, never()).updateStatus(watch);
    }

    public void testAckWatchNotAck() throws Exception {
        DateTime now = new DateTime(Clock.systemUTC().millis(), UTC);
        Watch watch = mock(Watch.class);
        when(watch.ack(now)).thenReturn(false);
        WatchStatus status = new WatchStatus(now, emptyMap());
        when(watch.status()).thenReturn(status);
        when(watchStore.get("_id")).thenReturn(watch);

        WatchStatus result = watcherService.ackWatch("_id", Strings.EMPTY_ARRAY);
        assertThat(result, not(sameInstance(status)));

        verify(watchStore, never()).updateStatus(watch);
    }

    public void testAckWatchNoWatch() throws Exception {
        when(watchStore.get("_id")).thenReturn(null);
        expectThrows(IllegalArgumentException.class, () -> watcherService.ackWatch("_id", Strings.EMPTY_ARRAY));
        verify(watchStore, never()).updateStatus(any(Watch.class));
    }
}
