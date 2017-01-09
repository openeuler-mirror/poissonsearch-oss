/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring;

import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.xpack.monitoring.exporter.ExportException;
import org.elasticsearch.xpack.monitoring.exporter.Exporters;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringDoc;
import org.junit.After;
import org.junit.Before;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MonitoringServiceTests extends ESTestCase {

    TestThreadPool threadPool;
    MonitoringService monitoringService;
    ClusterService clusterService;
    ClusterSettings clusterSettings;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getTestName());
        clusterService = mock(ClusterService.class);
        clusterSettings = new ClusterSettings(Settings.EMPTY, new HashSet<>(MonitoringSettings.getSettings()));
        when(clusterService.getClusterSettings()).thenReturn(clusterSettings);
    }

    @After
    public void terminate() throws Exception {
        if (monitoringService != null) {
            monitoringService.close();
        }
        terminate(threadPool);
    }

    public void testIsMonitoringActive() throws Exception {
        monitoringService = new MonitoringService(Settings.EMPTY, clusterSettings, threadPool, emptySet(), new CountingExporter());

        monitoringService.start();
        assertBusy(() -> assertTrue(monitoringService.isStarted()));
        assertTrue(monitoringService.isMonitoringActive());

        monitoringService.stop();
        assertBusy(() -> assertFalse(monitoringService.isStarted()));
        assertFalse(monitoringService.isMonitoringActive());

        monitoringService.start();
        assertBusy(() -> assertTrue(monitoringService.isStarted()));
        assertTrue(monitoringService.isMonitoringActive());

        monitoringService.close();
        assertBusy(() -> assertFalse(monitoringService.isStarted()));
        assertFalse(monitoringService.isMonitoringActive());
    }

    public void testInterval() throws Exception {
        Settings settings = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), TimeValue.MINUS_ONE).build();

        CountingExporter exporter = new CountingExporter();
        monitoringService = new MonitoringService(settings, clusterSettings, threadPool, emptySet(), exporter);

        monitoringService.start();
        assertBusy(() -> assertTrue(monitoringService.isStarted()));
        assertFalse("interval -1 does not start the monitoring execution", monitoringService.isMonitoringActive());
        assertEquals(0, exporter.getExportsCount());

        monitoringService.setInterval(TimeValue.timeValueSeconds(1));
        assertTrue(monitoringService.isMonitoringActive());
        assertBusy(() -> assertThat(exporter.getExportsCount(), greaterThan(0)));

        monitoringService.setInterval(TimeValue.timeValueMillis(100));
        assertFalse(monitoringService.isMonitoringActive());

        monitoringService.setInterval(TimeValue.MINUS_ONE);
        assertFalse(monitoringService.isMonitoringActive());
    }

    public void testSkipExecution() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        final BlockingExporter exporter = new BlockingExporter(latch);

        Settings settings = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), MonitoringSettings.MIN_INTERVAL).build();
        monitoringService = new MonitoringService(settings, clusterSettings, threadPool, emptySet(), exporter);

        logger.debug("start the monitoring service");
        monitoringService.start();
        assertBusy(() -> assertTrue(monitoringService.isStarted()));

        logger.debug("wait for the monitoring execution to be started");
        assertBusy(() -> assertThat(exporter.getExportsCount(), equalTo(1)));

        logger.debug("cancel current execution to avoid further execution once the latch is unblocked");
        monitoringService.cancelExecution();

        logger.debug("unblock the exporter");
        latch.countDown();

        logger.debug("verify that it hasn't been called more than one");
        assertThat(exporter.getExportsCount(), equalTo(1));
    }

    class CountingExporter extends Exporters {

        private final AtomicInteger exports = new AtomicInteger(0);

        public CountingExporter() {
            super(Settings.EMPTY, Collections.emptyMap(), clusterService);
        }

        @Override
        public void export(Collection<MonitoringDoc> docs) throws ExportException {
            exports.incrementAndGet();
        }

        int getExportsCount() {
            return exports.get();
        }

        @Override
        protected void doStart() {
        }

        @Override
        protected void doStop() {
        }

        @Override
        protected void doClose() {
        }
    }

    class BlockingExporter extends CountingExporter {

        private final CountDownLatch latch;

        BlockingExporter(CountDownLatch latch) {
            super();
            this.latch = latch;
        }

        @Override
        public void export(Collection<MonitoringDoc> docs) throws ExportException {
            super.export(docs);
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new ExportException("BlockingExporter failed", e);
            }
        }

        @Override
        protected void doStart() {
        }

        @Override
        protected void doStop() {
        }

        @Override
        protected void doClose() {
        }
    }
}
