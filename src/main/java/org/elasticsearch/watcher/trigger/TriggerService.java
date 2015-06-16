/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.trigger;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 *
 */
public class TriggerService extends AbstractComponent {

    private final Listeners listeners;
    private final ImmutableMap<String, TriggerEngine> engines;

    @Inject
    public TriggerService(Settings settings, Set<TriggerEngine> engines) {
        super(settings);
        listeners = new Listeners();
        ImmutableMap.Builder<String, TriggerEngine> builder = ImmutableMap.builder();
        for (TriggerEngine engine : engines) {
            builder.put(engine.type(), engine);
            engine.register(listeners);
        }
        this.engines = builder.build();
    }

    public synchronized void start(Collection<? extends TriggerEngine.Job> jobs) {
        for (TriggerEngine engine : engines.values()) {
            engine.start(jobs);
        }
    }

    public synchronized void stop() {
        for (TriggerEngine engine : engines.values()) {
            engine.stop();
        }
    }

    public void add(TriggerEngine.Job job) {
        engines.get(job.trigger().type()).add(job);
    }

    /**
     * Removes the job associated with the given name from this trigger service.
     *
     * @param jobName   The name of the job to remove
     * @return          {@code true} if the job existed and removed, {@code false} otherwise.
     */
    public boolean remove(String jobName) {
        for (TriggerEngine engine : engines.values()) {
            if (engine.remove(jobName)) {
                return true;
            }
        }
        return false;
    }

    public void register(TriggerEngine.Listener listener) {
        listeners.add(listener);
    }

    public TriggerEvent simulateEvent(String type, String jobId, Map<String, Object> data) {
        TriggerEngine engine = engines.get(type);
        if (engine == null) {
            throw new TriggerException("could not simulate trigger event. unknown trigger type [{}]", type);
        }
        return engine.simulateEvent(jobId, data, this);
    }

    public Trigger parseTrigger(String jobName, XContentParser parser) throws IOException {
        XContentParser.Token token = parser.currentToken();
        assert token == XContentParser.Token.START_OBJECT;
        token = parser.nextToken();
        if (token != XContentParser.Token.FIELD_NAME) {
            throw new TriggerException("could not parse trigger for [{}]. expected trigger type string field, but found [{}]", jobName, token);
        }
        String type = parser.text();
        token = parser.nextToken();
        if (token != XContentParser.Token.START_OBJECT) {
            throw new TriggerException("could not parse trigger [{}] for [{}]. expected trigger an object as the trigger body, but found [{}]", type, jobName, token);
        }
        Trigger trigger = parseTrigger(jobName, type, parser);
        token = parser.nextToken();
        if (token != XContentParser.Token.END_OBJECT) {
            throw new TriggerException("could not parse trigger [{}] for [{}]. expected [END_OBJECT] token, but found [{}]", type, jobName, token);
        }
        return trigger;
    }

    public Trigger parseTrigger(String jobName, String type, XContentParser parser) throws IOException {
        TriggerEngine engine = engines.get(type);
        if (engine == null) {
            throw new TriggerException("could not parse trigger [{}] for [{}]. unknown trigger type [{}]", type, jobName, type);
        }
        return engine.parseTrigger(jobName, parser);
    }

    public TriggerEvent parseTriggerEvent(String watchId, String context, XContentParser parser) throws IOException {
        XContentParser.Token token = parser.currentToken();
        assert token == XContentParser.Token.START_OBJECT;
        token = parser.nextToken();
        if (token != XContentParser.Token.FIELD_NAME) {
            throw new TriggerException("could not parse trigger event for [{}] for watch [{}]. expected trigger type string field, but found [{}]", context, watchId, token);
        }
        String type = parser.text();
        token = parser.nextToken();
        if (token != XContentParser.Token.START_OBJECT) {
            throw new TriggerException("could not parse trigger event for [{}] for watch [{}]. expected trigger an object as the trigger body, but found [{}]", context, watchId, token);
        }
        TriggerEvent trigger = parseTriggerEvent(watchId, context, type, parser);
        token = parser.nextToken();
        if (token != XContentParser.Token.END_OBJECT) {
            throw new TriggerException("could not parse trigger [{}] for [{}]. expected [END_OBJECT] token, but found [{}]", type, context, token);
        }
        return trigger;
    }

    public TriggerEvent parseTriggerEvent(String watchId, String context, String type, BytesReference bytes) throws IOException {
        XContentParser parser = XContentHelper.createParser(bytes);
        parser.nextToken();
        return parseTriggerEvent(watchId, context, type, parser);
    }


    public TriggerEvent parseTriggerEvent(String watchId, String context, String type, XContentParser parser) throws IOException {
        TriggerEngine engine = engines.get(type);
        if (engine == null) {
            throw new TriggerException("Unknown trigger type [{}]", type);
        }
        return engine.parseTriggerEvent(this, watchId, context, parser);
    }

    static class Listeners implements TriggerEngine.Listener {

        private List<TriggerEngine.Listener> listeners = new CopyOnWriteArrayList<>();

        public void add(TriggerEngine.Listener listener) {
            listeners.add(listener);
        }

        @Override
        public void triggered(Iterable<TriggerEvent> events) {
            for (TriggerEngine.Listener listener : listeners) {
                listener.triggered(events);
            }
        }
    }

}
