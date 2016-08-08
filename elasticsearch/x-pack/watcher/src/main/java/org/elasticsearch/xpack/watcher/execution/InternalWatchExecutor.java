/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.execution;

import java.util.concurrent.BlockingQueue;
import java.util.stream.Stream;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.util.concurrent.EsThreadPoolExecutor;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.XPackPlugin;

public class InternalWatchExecutor implements WatchExecutor {

    public static final String THREAD_POOL_NAME = XPackPlugin.WATCHER;

    private final ThreadPool threadPool;

    @Inject
    public InternalWatchExecutor(ThreadPool threadPool) {
        this.threadPool = threadPool;
    }

    @Override
    public BlockingQueue<Runnable> queue() {
        return executor().getQueue();
    }

    @Override
    public Stream<Runnable> tasks() {
        return executor().getTasks();
    }

    @Override
    public long largestPoolSize() {
        return executor().getLargestPoolSize();
    }

    @Override
    public void execute(Runnable runnable) {
        executor().execute(runnable);
    }

    private EsThreadPoolExecutor executor() {
        return (EsThreadPoolExecutor) threadPool.executor(THREAD_POOL_NAME);
    }

}
