/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerting;

import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.unit.TimeValue;

import java.util.List;

/**
 * Created by brian on 8/12/14.
 */
public class Alert {
    private final String alertName;
    private String queryName;
    private AlertTrigger trigger;
    private TimeValue timePeriod;
    private List<AlertAction> actions;
    private String schedule;
    private DateTime lastRan;

    public List<String> indices() {
        return indices;
    }

    public void indices(List<String> indices) {
        this.indices = indices;
    }

    private List<String> indices;


    public String alertName() {
        return alertName;
    }

    public String queryName() {
        return queryName;
    }

    public void queryName(String queryName) {
        this.queryName = queryName;
    }

    public AlertTrigger trigger() {
        return trigger;
    }

    public void trigger(AlertTrigger trigger) {
        this.trigger = trigger;
    }

    public TimeValue timePeriod() {
        return timePeriod;
    }

    public void timePeriod(TimeValue timePeriod) {
        this.timePeriod = timePeriod;
    }

    public List<AlertAction> actions() {
        return actions;
    }

    public void actions(List<AlertAction> action) {
        this.actions = action;
    }

    public String schedule() {
        return schedule;
    }

    public void schedule(String schedule) {
        this.schedule = schedule;
    }

    public DateTime lastRan() {
        return lastRan;
    }

    public void lastRan(DateTime lastRan) {
        this.lastRan = lastRan;
    }

    public Alert(String alertName, String queryName, AlertTrigger trigger,
                 TimeValue timePeriod, List<AlertAction> actions, String schedule, DateTime lastRan,
                 List<String> indices){
        this.alertName = alertName;
        this.queryName = queryName;
        this.trigger = trigger;
        this.timePeriod = timePeriod;
        this.actions = actions;
        this.lastRan = lastRan;
        this.schedule = schedule;
        this.indices = indices;
    }

    public String toJSON(){
        return null;
    }
}
