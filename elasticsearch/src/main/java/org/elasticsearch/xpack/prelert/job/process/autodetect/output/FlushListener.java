/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.output;

import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class FlushListener {

    final ConcurrentMap<String, CountDownLatch> awaitingFlushed = new ConcurrentHashMap<>();
    final AtomicBoolean cleared = new AtomicBoolean(false);

    boolean waitForFlush(String flushId, long timeout) {
        if (cleared.get()) {
            return false;
        }

        CountDownLatch latch = awaitingFlushed.computeIfAbsent(flushId, (key) -> new CountDownLatch(1));
        try {
            return latch.await(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            // the flush id will no longer be used from this point, so we can remove it.
            awaitingFlushed.remove(flushId);
        }
    }

    void acknowledgeFlush(String flushId) {
        // acknowledgeFlush(...) could be called before waitForFlush(...)
        // a flush api call writes a flush command to the analytical process and then via a different thread the
        // result reader then reads whether the flush has been acked.
        CountDownLatch latch = awaitingFlushed.computeIfAbsent(flushId, (key) -> new CountDownLatch(1));
        latch.countDown();
    }

    void clear() {
        if (cleared.compareAndSet(false, true)) {
            Iterator<ConcurrentMap.Entry<String, CountDownLatch>> latches = awaitingFlushed.entrySet().iterator();
            while (latches.hasNext()) {
                latches.next().getValue().countDown();
                latches.remove();
            }
        }
    }

}
