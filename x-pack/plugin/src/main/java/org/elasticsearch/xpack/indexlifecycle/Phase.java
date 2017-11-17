/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.indexlifecycle;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.Index;
import org.elasticsearch.xpack.scheduler.SchedulerEngine;
import org.elasticsearch.xpack.scheduler.SchedulerEngine.Schedule;

import java.io.IOException;
import java.util.List;

public class Phase extends SchedulerEngine.Job implements ToXContentObject {

    public static final ParseField NAME_FIELD = new ParseField("name");
    public static final ParseField ID_FIELD = new ParseField("id");
    public static final ParseField ACTIONS_FIELD = new ParseField("actions");
    public static final ParseField AFTER_FIELD = new ParseField("after");

    private String name;
    private List<Action> actions;
    private Client client;
    private TimeValue after;

    public Phase(String name, Index index, long creationDate, TimeValue after, List<Action> actions, Client client) {
        super(index.getName() + "-" + name, getSchedule(creationDate, after));
        this.name = name;
        this.client = client;
        this.after = after;
        this.actions = actions;
    }

    public TimeValue getAfter() {
        return after;
    }

    private static Schedule getSchedule(long creationDate, TimeValue after) {
        SchedulerEngine.Schedule schedule = (startTime, now) -> {
            ESLoggerFactory.getLogger("INDEX-LIFECYCLE-PLUGIN")
                .error("calculating schedule with creationTime:" + creationDate + ", and now:" + now);
            if (startTime == now) {
                return creationDate + after.getMillis();
            } else {
                return -1; // do not schedule another delete after already deleted
            }
        };
        return schedule;
    }

    public Phase(String name, List<Action> actions, Schedule schedule, Client client) {
        super(name, schedule);
        this.name = name;
        this.actions = actions;
        this.client = client;
    }

    protected void performActions() {
        for (Action action : actions) {
            action.execute(client);
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(NAME_FIELD.getPreferredName(), name);
        builder.field(ID_FIELD.getPreferredName(), name);
        builder.field(AFTER_FIELD.getPreferredName(), after);
        builder.array(ACTIONS_FIELD.getPreferredName(), actions);
        builder.endObject();
        return builder;
    }

}
