/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher;


import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.PeriodType;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.watcher.execution.ExecutionService;
import org.elasticsearch.watcher.support.clock.Clock;
import org.elasticsearch.watcher.trigger.TriggerService;
import org.elasticsearch.watcher.watch.Watch;
import org.elasticsearch.watcher.watch.WatchLockService;
import org.elasticsearch.watcher.watch.WatchStatus;
import org.elasticsearch.watcher.watch.WatchStore;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;

public class WatcherService extends AbstractComponent {

    private final Clock clock;
    private final TriggerService triggerService;
    private final Watch.Parser watchParser;
    private final WatchStore watchStore;
    private final WatchLockService watchLockService;
    private final ExecutionService executionService;
    private final AtomicReference<WatcherState> state = new AtomicReference<>(WatcherState.STOPPED);

    @Inject
    public WatcherService(Settings settings, Clock clock, TriggerService triggerService, WatchStore watchStore,
                          Watch.Parser watchParser, ExecutionService executionService, WatchLockService watchLockService) {
        super(settings);
        this.clock = clock;
        this.triggerService = triggerService;
        this.watchStore = watchStore;
        this.watchParser = watchParser;
        this.watchLockService = watchLockService;
        this.executionService = executionService;
    }

    public void start(ClusterState clusterState) {
        if (state.compareAndSet(WatcherState.STOPPED, WatcherState.STARTING)) {
            logger.info("starting watch service...");
            watchLockService.start();

            // Try to load watch store before the execution service, b/c action depends on watch store
            watchStore.start(clusterState);
            executionService.start(clusterState);
            triggerService.start(watchStore.watches().values());
            state.set(WatcherState.STARTED);
            logger.info("watch service has started");
        }
    }

    public boolean validate(ClusterState state) {
        return watchStore.validate(state) && executionService.validate(state);
    }

    public void stop() {
        if (state.compareAndSet(WatcherState.STARTED, WatcherState.STOPPING)) {
            logger.info("stopping watch service...");
            triggerService.stop();
            executionService.stop();
            try {
                watchLockService.stop();
            } catch (WatchLockService.TimeoutException we) {
                logger.warn("error stopping WatchLockService", we);
            }
            watchStore.stop();
            state.set(WatcherState.STOPPED);
            logger.info("watch service has stopped");
        }
    }

    public WatchStore.WatchDelete deleteWatch(String id, TimeValue timeout, final boolean force) {
        ensureStarted();
        WatchLockService.Lock lock = null;
        if (!force) {
            lock = watchLockService.tryAcquire(id, timeout);
            if (lock == null) {
                throw new TimeoutException("could not delete watch [{}] within [{}]... wait and try again. If this error continues to occur there is a high chance that the watch execution is stuck (either due to unresponsive external system such as an email service, or due to a bad script", id, timeout.format(PeriodType.seconds()));
            }
        }
        try {
            WatchStore.WatchDelete delete = watchStore.delete(id, force);
            if (delete.deleteResponse().isFound()) {
                triggerService.remove(id);
            }
            return delete;
        } finally {
            if (lock != null) {
                lock.release();
            }
        }
    }

    public IndexResponse putWatch(String id, BytesReference watchSource, TimeValue timeout) {
        ensureStarted();
        WatchLockService.Lock lock = watchLockService.tryAcquire(id, timeout);
        if (lock == null) {
            throw new TimeoutException("could not put watch [{}] within [{}]... wait and try again. If this error continues to occur there is a high chance that the watch execution is stuck (either due to unresponsive external system such as an email service, or due to a bad script", id, timeout.format(PeriodType.seconds()));
        }
        try {
            Watch watch = watchParser.parseWithSecrets(id, false, watchSource);
            WatchStore.WatchPut result = watchStore.put(watch);
            if (result.previous() == null || !result.previous().trigger().equals(result.current().trigger())) {
                triggerService.add(result.current());
            }
            return result.indexResponse();
        } catch (Exception e) {
            logger.warn("failed to put watch [{}]", e, id);
            throw new WatcherException("failed to put watch [{}]", e, id);
        } finally {
            lock.release();
        }
    }

    /**
     * TODO: add version, fields, etc support that the core get api has as well.
     */
    public Watch getWatch(String name) {
        return watchStore.get(name);
    }

    public WatcherState state() {
        return state.get();
    }

    /**
     * Acks the watch if needed
     */
    public WatchStatus ackWatch(String id, TimeValue timeout) {
        ensureStarted();
        WatchLockService.Lock lock = watchLockService.tryAcquire(id, timeout);
        if (lock == null) {
            throw new TimeoutException("could not ack watch [{}] within [{}]... wait and try again. If this error continues to occur there is a high chance that the watch execution is stuck (either due to unresponsive external system such as an email service, or due to a bad script", id, timeout.format(PeriodType.seconds()));
        }
        try {
            Watch watch = watchStore.get(id);
            if (watch == null) {
                throw new WatcherException("watch [{}] does not exist", id);
            }
            if (watch.ack(clock.now(UTC), "_all")) {
                try {
                    watchStore.updateStatus(watch);
                } catch (IOException ioe) {
                    throw new WatcherException("failed to update the watch [{}] on ack", ioe, watch.id());
                } catch (VersionConflictEngineException vcee) {
                    throw new WatcherException("failed to update the watch [{}] on ack, perhaps it was force deleted", vcee, watch.id());
                }
            }
            // we need to create a safe copy of the status
            return new WatchStatus(watch.status());
        } finally {
            lock.release();
        }
    }

    public long watchesCount() {
        return watchStore.watches().size();
    }

    private void ensureStarted() {
        if (state.get() != WatcherState.STARTED) {
            throw new ElasticsearchIllegalStateException("not started");
        }
    }

    public static class TimeoutException extends WatcherException {

        public TimeoutException(String msg, Object... args) {
            super(msg, args);
        }
    }
}
