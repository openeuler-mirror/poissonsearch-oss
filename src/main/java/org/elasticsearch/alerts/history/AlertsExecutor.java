/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.history;

import java.util.concurrent.BlockingQueue;

/**
 *
 */
public interface AlertsExecutor {

    BlockingQueue queue();

    long largestPoolSize();

    void execute(Runnable runnable);

}