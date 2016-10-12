/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.monitoring.MonitoredSystem;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.monitoring.exporter.local.LocalExporter;
import org.elasticsearch.xpack.monitoring.cleaner.CleanerService;
import org.elasticsearch.xpack.security.InternalClient;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class ExportersTests extends ESTestCase {
    private Exporters exporters;
    private Map<String, Exporter.Factory> factories;
    private ClusterService clusterService;
    private ClusterSettings clusterSettings;

    @Before
    public void init() throws Exception {
        factories = new HashMap<>();

        InternalClient client = mock(InternalClient.class);
        clusterService = mock(ClusterService.class);
        clusterSettings = new ClusterSettings(Settings.EMPTY, new HashSet<>(Arrays.asList(MonitoringSettings.COLLECTORS,
            MonitoringSettings.INTERVAL, MonitoringSettings.EXPORTERS_SETTINGS)));
        when(clusterService.getClusterSettings()).thenReturn(clusterSettings);

        // we always need to have the local exporter as it serves as the default one
        factories.put(LocalExporter.TYPE, config -> new LocalExporter(config, client, clusterService, mock(CleanerService.class)));

        exporters = new Exporters(Settings.EMPTY, factories, clusterService);
    }

    public void testInitExportersDefault() throws Exception {
        factories.put("_type", TestExporter::new);
        Map<String, Exporter> internalExporters = exporters.initExporters(Settings.builder().build());

        assertThat(internalExporters, notNullValue());
        assertThat(internalExporters.size(), is(1));
        assertThat(internalExporters, hasKey("default_" + LocalExporter.TYPE));
        assertThat(internalExporters.get("default_" + LocalExporter.TYPE), instanceOf(LocalExporter.class));
    }

    public void testInitExportersSingle() throws Exception {
        factories.put("_type", TestExporter::new);
        Map<String, Exporter> internalExporters = exporters.initExporters(Settings.builder()
                .put("_name.type", "_type")
                .build());

        assertThat(internalExporters, notNullValue());
        assertThat(internalExporters.size(), is(1));
        assertThat(internalExporters, hasKey("_name"));
        assertThat(internalExporters.get("_name"), instanceOf(TestExporter.class));
        assertThat(internalExporters.get("_name").config().type(), is("_type"));
    }

    public void testInitExportersSingleDisabled() throws Exception {
        factories.put("_type", TestExporter::new);
        Map<String, Exporter> internalExporters = exporters.initExporters(Settings.builder()
                .put("_name.type", "_type")
                .put("_name.enabled", false)
                .build());

        assertThat(internalExporters, notNullValue());

        // the only configured exporter is disabled... yet we intentionally don't fallback on the default
        assertThat(internalExporters.size(), is(0));
    }

    public void testInitExportersSingleUnknownType() throws Exception {
        try {
            exporters.initExporters(Settings.builder()
                    .put("_name.type", "unknown_type")
                    .build());
            fail("Expected SettingsException");
        } catch (SettingsException e) {
            assertThat(e.getMessage(), containsString("unknown exporter type [unknown_type]"));
        }
    }

    public void testInitExportersSingleMissingExporterType() throws Exception {
        try {
            exporters.initExporters(Settings.builder()
                    .put("_name.foo", "bar")
                    .build());
            fail("Expected SettingsException");
        } catch (SettingsException e) {
            assertThat(e.getMessage(), containsString("missing exporter type for [_name]"));
        }
    }

    public void testInitExportersMultipleSameType() throws Exception {
        factories.put("_type", TestExporter::new);
        Map<String, Exporter> internalExporters = exporters.initExporters(Settings.builder()
                .put("_name0.type", "_type")
                .put("_name1.type", "_type")
                .build());

        assertThat(internalExporters, notNullValue());
        assertThat(internalExporters.size(), is(2));
        assertThat(internalExporters, hasKey("_name0"));
        assertThat(internalExporters.get("_name0"), instanceOf(TestExporter.class));
        assertThat(internalExporters.get("_name0").config().type(), is("_type"));
        assertThat(internalExporters, hasKey("_name1"));
        assertThat(internalExporters.get("_name1"), instanceOf(TestExporter.class));
        assertThat(internalExporters.get("_name1").config().type(), is("_type"));
    }

    public void testInitExportersMultipleSameTypeSingletons() throws Exception {
        factories.put("_type", TestSingletonExporter::new);
        SettingsException e = expectThrows(SettingsException.class, () ->
            exporters.initExporters(Settings.builder()
                    .put("_name0.type", "_type")
                    .put("_name1.type", "_type")
                    .build())
        );
        assertThat(e.getMessage(), containsString("multiple [_type] exporters are configured. there can only be one"));
    }

    public void testSettingsUpdate() throws Exception {
        factories.put("_type", TestExporter::new);

        final AtomicReference<Settings> settingsHolder = new AtomicReference<>();

        Settings nodeSettings = Settings.builder()
                .put("xpack.monitoring.exporters._name0.type", "_type")
                .put("xpack.monitoring.exporters._name1.type", "_type")
                .build();
        clusterSettings = new ClusterSettings(nodeSettings, new HashSet<>(Arrays.asList(MonitoringSettings.EXPORTERS_SETTINGS)));
        when(clusterService.getClusterSettings()).thenReturn(clusterSettings);

        exporters = new Exporters(nodeSettings, factories, clusterService) {
            @Override
            Map<String, Exporter> initExporters(Settings settings) {
                settingsHolder.set(settings);
                return super.initExporters(settings);
            }
        };
        exporters.start();

        assertThat(settingsHolder.get(), notNullValue());
        Map<String, String> settings = settingsHolder.get().getAsMap();
        assertThat(settings.size(), is(2));
        assertThat(settings, hasEntry("_name0.type", "_type"));
        assertThat(settings, hasEntry("_name1.type", "_type"));

        Settings update = Settings.builder()
                .put("xpack.monitoring.exporters._name0.foo", "bar")
                .put("xpack.monitoring.exporters._name1.foo", "bar")
                .build();
        clusterSettings.applySettings(update);
        assertThat(settingsHolder.get(), notNullValue());
        settings = settingsHolder.get().getAsMap();
        assertThat(settings.size(), is(4));
        assertThat(settings, hasEntry("_name0.type", "_type"));
        assertThat(settings, hasEntry("_name0.foo", "bar"));
        assertThat(settings, hasEntry("_name1.type", "_type"));
        assertThat(settings, hasEntry("_name1.foo", "bar"));
    }

    public void testOpenBulkOnMaster() throws Exception {
        Exporter.Factory factory = new MockFactory(false);
        Exporter.Factory masterOnlyFactory = new MockFactory(true);
        factories.put("mock", factory);
        factories.put("mock_master_only", masterOnlyFactory);
        Exporters exporters = new Exporters(Settings.builder()
                .put("xpack.monitoring.exporters._name0.type", "mock")
                .put("xpack.monitoring.exporters._name1.type", "mock_master_only")
                .build(), factories, clusterService);
        exporters.start();

        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        when(nodes.isLocalNodeElectedMaster()).thenReturn(true);
        when(clusterService.state()).thenReturn(ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
                .nodes(nodes).build());

        ExportBulk bulk = exporters.openBulk();
        assertThat(bulk, notNullValue());

        verify(exporters.getExporter("_name0"), times(1)).masterOnly();
        verify(exporters.getExporter("_name0"), times(1)).openBulk();
        verify(exporters.getExporter("_name1"), times(1)).masterOnly();
        verify(exporters.getExporter("_name1"), times(1)).openBulk();
    }

    public void testExportNotOnMaster() throws Exception {
        Exporter.Factory factory = new MockFactory(false);
        Exporter.Factory masterOnlyFactory = new MockFactory(true);
        factories.put("mock", factory);
        factories.put("mock_master_only", masterOnlyFactory);
        Exporters exporters = new Exporters(Settings.builder()
                .put("xpack.monitoring.exporters._name0.type", "mock")
                .put("xpack.monitoring.exporters._name1.type", "mock_master_only")
                .build(), factories, clusterService);
        exporters.start();

        DiscoveryNodes nodes = mock(DiscoveryNodes.class);
        when(nodes.isLocalNodeElectedMaster()).thenReturn(false);
        when(clusterService.state()).thenReturn(ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
                .nodes(nodes).build());

        ExportBulk bulk = exporters.openBulk();
        assertThat(bulk, notNullValue());

        verify(exporters.getExporter("_name0"), times(1)).masterOnly();
        verify(exporters.getExporter("_name0"), times(1)).openBulk();
        verify(exporters.getExporter("_name1"), times(1)).masterOnly();
        verify(exporters.getExporter("_name1"), times(1)).isSingleton();
        verifyNoMoreInteractions(exporters.getExporter("_name1"));
    }

    public void testEmptyPipeline() throws IOException {
        String json = Exporter.emptyPipeline(XContentType.JSON).string();

        // ensure the description starts with the API version
        assertThat(json, containsString("\"description\":\"" + MonitoringTemplateUtils.TEMPLATE_VERSION + ":"));
        assertThat(json, containsString("\"processors\":[]"));
    }

    /**
     * This test creates N threads that export a random number of document
     * using a {@link Exporters} instance.
     */
    public void testConcurrentExports() throws Exception {
        final int nbExporters = randomIntBetween(1, 5);

        logger.info("--> creating {} exporters", nbExporters);
        Settings.Builder settings = Settings.builder();
        for (int i = 0; i < nbExporters; i++) {
            settings.put("xpack.monitoring.exporters._name" + String.valueOf(i) + ".type", "record");
        }

        factories.put("record", CountingExporter::new);

        Exporters exporters = new Exporters(settings.build(), factories, clusterService);
        exporters.start();

        final Thread[] threads = new Thread[3 + randomInt(7)];
        final CyclicBarrier barrier = new CyclicBarrier(threads.length);
        final List<Throwable> exceptions = new CopyOnWriteArrayList<>();

        int total = 0;

        logger.info("--> exporting documents using {} threads", threads.length);
        for (int i = 0; i < threads.length; i++) {
            int nbDocs = randomIntBetween(10, 50);
            total += nbDocs;

            final int threadNum = i;
            final int threadDocs = nbDocs;

            logger.debug("--> exporting thread [{}] exports {} documents", threadNum, threadDocs);
            threads[i] = new Thread(new AbstractRunnable() {
                @Override
                public void onFailure(Exception e) {
                    logger.error("unexpected error in exporting thread", e);
                    exceptions.add(e);
                }

                @Override
                protected void doRun() throws Exception {
                    List<MonitoringDoc> docs = new ArrayList<>();
                    for (int n = 0; n < threadDocs; n++) {
                        docs.add(new MonitoringDoc(MonitoredSystem.ES.getSystem(), Version.CURRENT.toString()));
                    }
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        exporters.export(docs);
                        logger.debug("--> thread [{}] successfully exported {} documents", threadNum, threadDocs);
                    } catch (Exception e) {
                        logger.debug("--> thread [{}] failed to export {} documents", threadNum, threadDocs);
                    }

                }
            }, "export_thread_" + i);
            threads[i].start();
        }

        logger.info("--> waiting for threads to exports {} documents", total);
        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(exceptions, empty());
        for (Exporter exporter : exporters) {
            assertThat(exporter, instanceOf(CountingExporter.class));
            assertThat(((CountingExporter) exporter).getExportedCount(), equalTo(total));
        }

        exporters.close();
    }

    static class TestExporter extends Exporter {
        public TestExporter(Config config) {
            super(config);
        }

        @Override
        public ExportBulk openBulk() {
            return mock(ExportBulk.class);
        }

        @Override
        public void doClose() {
        }
    }

    static class TestSingletonExporter extends TestExporter {
        TestSingletonExporter(Config config) {
            super(config);
        }

        @Override
        public boolean isSingleton() {
            return true;
        }
    }


    static class MockFactory implements Exporter.Factory {
        private final boolean masterOnly;

        public MockFactory(boolean masterOnly) {
            this.masterOnly = masterOnly;
        }

        @Override
        public Exporter create(Exporter.Config config) {
            Exporter exporter = mock(Exporter.class);
            when(exporter.name()).thenReturn(config.name());
            when(exporter.masterOnly()).thenReturn(masterOnly);
            when(exporter.openBulk()).thenReturn(mock(ExportBulk.class));
            return exporter;
        }
    }

    static class CountingExporter extends Exporter {

        private static final AtomicInteger count = new AtomicInteger(0);
        private List<CountingBulk> bulks = new CopyOnWriteArrayList<>();

        public CountingExporter(Config config) {
            super(config);
        }

        @Override
        public ExportBulk openBulk() {
            CountingBulk bulk = new CountingBulk(config.type() + "#" + count.getAndIncrement());
            bulks.add(bulk);
            return bulk;
        }

        @Override
        public void doClose() {
        }

        public int getExportedCount() {
            int exported = 0;
            for (CountingBulk bulk : bulks) {
                exported += bulk.getCount();
            }
            return exported;
        }
    }

    static class CountingBulk extends ExportBulk {

        private final AtomicInteger count = new AtomicInteger();

        public CountingBulk(String name) {
            super(name);
        }

        @Override
        protected void doAdd(Collection<MonitoringDoc> docs) throws ExportException {
            count.addAndGet(docs.size());
        }

        @Override
        protected void doFlush() {
        }

        @Override
        protected void doClose() throws ExportException {
        }

        int getCount() {
            return count.get();
        }
    }
}
