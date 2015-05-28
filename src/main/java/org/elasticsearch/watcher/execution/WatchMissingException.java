/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.execution;

import org.elasticsearch.watcher.WatcherException;

public class WatchMissingException extends WatcherException {

    public WatchMissingException(String msg, Object... args) {
        super(msg, args);
    }

    public WatchMissingException(String msg, Throwable cause, Object... args) {
        super(msg, cause, args);
    }
}
