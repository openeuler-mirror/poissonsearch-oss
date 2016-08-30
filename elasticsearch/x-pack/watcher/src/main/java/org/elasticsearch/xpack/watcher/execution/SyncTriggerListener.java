/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.execution;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.watcher.trigger.TriggerEngine;
import org.elasticsearch.xpack.watcher.trigger.TriggerEvent;
import org.elasticsearch.xpack.watcher.trigger.TriggerService;

import static java.util.stream.StreamSupport.stream;

/**
 */
public class SyncTriggerListener implements TriggerEngine.Listener {

    private final ExecutionService executionService;
    private final Logger logger;

    @Inject
    public SyncTriggerListener(Settings settings, ExecutionService executionService, TriggerService triggerService) {
        this.logger = Loggers.getLogger(SyncTriggerListener.class, settings);
        this.executionService = executionService;
        triggerService.register(this);
    }

    @Override
    public void triggered(Iterable<TriggerEvent> events) {
        try {
            executionService.processEventsSync(events);
        } catch (Exception e) {
            logger.error(
                    new ParameterizedMessage(
                            "failed to process triggered events [{}]",
                            (Object) stream(events.spliterator(), false).toArray(size -> new TriggerEvent[size])),
                    e);
        }
    }

}
