/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.trigger;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

public interface TriggerEngine<T extends Trigger, E extends TriggerEvent> {

    String type();

    /**
     * It's the responsibility of the trigger engine implementation to select the appropriate jobs
     * from the given list of jobs
     */
    void start(Collection<Job> jobs);

    void stop();

    void register(Listener listener);

    void add(Job job);

    /**
     * Removes the job associated with the given name from this trigger engine.
     *
     * @param jobId   The name of the job to remove
     * @return          {@code true} if the job existed and removed, {@code false} otherwise.
     */
    boolean remove(String jobId);

    E simulateEvent(String jobId, @Nullable Map<String, Object> data, TriggerService service);

    T parseTrigger(String context, XContentParser parser) throws IOException;

    E parseTriggerEvent(TriggerService service, String watchId, String context, XContentParser parser) throws IOException;

    interface Listener {

        void triggered(Iterable<TriggerEvent> events);

    }

    interface Job {

        String id();

        Trigger trigger();
    }


}
