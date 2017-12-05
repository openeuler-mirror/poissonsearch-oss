/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter.local;

import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.monitoring.MonitoringService;
import org.elasticsearch.xpack.monitoring.cleaner.CleanerService;
import org.elasticsearch.xpack.monitoring.exporter.Exporter;
import org.elasticsearch.xpack.monitoring.test.MonitoringIntegTestCase;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import static org.elasticsearch.xpack.monitoring.exporter.Exporter.CLUSTER_ALERTS_MANAGEMENT_SETTING;

/**
 * {@code LocalExporterIntegTestCase} offers a basis for integration tests for the {@link LocalExporter}.
 */
public abstract class LocalExporterIntegTestCase extends MonitoringIntegTestCase {

    protected final String exporterName = "_local";

    private static ThreadPool THREADPOOL;

    @BeforeClass
    public static void setupThreadPool() {
        THREADPOOL = new TestThreadPool(LocalExporterIntegTestCase.class.getName());
    }

    @AfterClass
    public static void cleanUpStatic() throws Exception {
        if (THREADPOOL != null) {
            terminate(THREADPOOL);
        }
    }

    protected Settings localExporterSettings() {
        return Settings.builder()
                       .put(MonitoringService.INTERVAL.getKey(), "-1")
                       .put("xpack.monitoring.exporters." + exporterName + ".type", LocalExporter.TYPE)
                       .put("xpack.monitoring.exporters." + exporterName +  ".enabled", false)
                       .put("xpack.monitoring.exporters." + exporterName +  "." + CLUSTER_ALERTS_MANAGEMENT_SETTING, false)
                       .put(XPackSettings.WATCHER_ENABLED.getKey(), false)
                       .put(NetworkModule.HTTP_ENABLED.getKey(), false)
                       .build();
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                       .put(super.nodeSettings(nodeOrdinal))
                       .put(localExporterSettings())
                       .build();
    }

    /**
     * Create a new {@link LocalExporter}. Expected usage:
     * <pre><code>
     * final Settings settings = Settings.builder().put("xpack.monitoring.exporters._local.type", "local").build();
     * try (LocalExporter exporter = createLocalExporter("_local", settings)) {
     *   // ...
     * }
     * </code></pre>
     *
     * @return Never {@code null}.
     */
    protected LocalExporter createLocalExporter() {
        final Settings settings = localExporterSettings();
        final XPackLicenseState licenseState = new XPackLicenseState();
        final Exporter.Config config =
                new Exporter.Config(exporterName, "local",
                                    settings, settings.getAsSettings("xpack.monitoring.exporters." + exporterName),
                                    clusterService(), licenseState);
        final CleanerService cleanerService =
                new CleanerService(settings, clusterService().getClusterSettings(), THREADPOOL, licenseState);

        return new LocalExporter(config, client(), cleanerService);
    }

}
