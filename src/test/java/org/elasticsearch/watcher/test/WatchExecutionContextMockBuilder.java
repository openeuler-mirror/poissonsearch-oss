/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.test;

import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.execution.Wid;
import org.elasticsearch.watcher.trigger.TriggerEvent;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.Watch;

import java.util.Collections;
import java.util.Map;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 *
 */
public class WatchExecutionContextMockBuilder {

    private final WatchExecutionContext ctx;
    private final Watch watch;

    public WatchExecutionContextMockBuilder(String watchId) {
        ctx = mock(WatchExecutionContext.class);
        watch = mock(Watch.class);
        when(watch.name()).thenReturn(watchId);
        when(ctx.watch()).thenReturn(watch);
        payload(Collections.<String, Object>emptyMap());
        metadata(Collections.<String, Object>emptyMap());
        time(watchId, DateTime.now(UTC));
    }

    public WatchExecutionContextMockBuilder wid(Wid wid) {
        when(ctx.id()).thenReturn(wid);
        return this;
    }

    public WatchExecutionContextMockBuilder payload(String key, Object value) {
        return payload(new Payload.Simple(MapBuilder.<String, Object>newMapBuilder().put(key, value).map()));
    }

    public WatchExecutionContextMockBuilder payload(Map<String, Object> payload) {
        return payload(new Payload.Simple(payload));
    }

    public WatchExecutionContextMockBuilder payload(Payload payload) {
        when(ctx.payload()).thenReturn(payload);
        return this;
    }

    public WatchExecutionContextMockBuilder time(String watchId, DateTime time) {
        return executionTime(time).triggerEvent(new ScheduleTriggerEvent(watchId, time, time));
    }

    public WatchExecutionContextMockBuilder executionTime(DateTime time) {
        when(ctx.executionTime()).thenReturn(time);
        return this;
    }

    public WatchExecutionContextMockBuilder triggerEvent(TriggerEvent event) {
        when(ctx.triggerEvent()).thenReturn(event);
        return this;
    }

    public WatchExecutionContextMockBuilder metadata(Map<String, Object> metadata) {
        when(watch.metadata()).thenReturn(metadata);
        return this;
    }

    public WatchExecutionContextMockBuilder metadata(String key, String value) {
        return metadata(MapBuilder.<String, Object>newMapBuilder().put(key, value).map());
    }

    public WatchExecutionContext buildMock() {
        return ctx;
    }
}
