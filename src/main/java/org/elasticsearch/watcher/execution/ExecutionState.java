/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.execution;

import org.elasticsearch.watcher.WatcherException;

import java.util.Locale;

public enum ExecutionState {

    EXECUTION_NOT_NEEDED,
    THROTTLED,
    EXECUTED,
    FAILED,
    DELETED_WHILE_QUEUED;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ExecutionState resolve(String id) {
        try {
            return valueOf(id.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException iae) {
            throw new WatcherException("unknown execution state [{}]", id);
        }
    }

    @Override
    public String toString() {
        return id();
    }

}
