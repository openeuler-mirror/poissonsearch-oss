/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring;

import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.XPackFeatureSet;
import org.elasticsearch.xpack.monitoring.agent.exporter.Exporter;
import org.elasticsearch.xpack.monitoring.agent.exporter.Exporters;
import org.elasticsearch.xpack.monitoring.agent.exporter.http.HttpExporter;
import org.elasticsearch.xpack.monitoring.agent.exporter.local.LocalExporter;
import org.elasticsearch.xpack.watcher.support.xcontent.XContentSource;
import org.junit.Before;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 *
 */
public class MonitoringFeatureSetTests extends ESTestCase {

    private XPackLicenseState licenseState;
    private NamedWriteableRegistry namedWriteableRegistry;
    private Exporters exporters;

    @Before
    public void init() throws Exception {
        licenseState = mock(XPackLicenseState.class);
        exporters = mock(Exporters.class);
        namedWriteableRegistry = mock(NamedWriteableRegistry.class);
    }

    public void testWritableRegistration() throws Exception {
        new MonitoringFeatureSet(Settings.EMPTY, licenseState, exporters, namedWriteableRegistry);
        verify(namedWriteableRegistry).register(eq(MonitoringFeatureSet.Usage.class), eq("xpack.usage.monitoring"), anyObject());
    }

    public void testAvailable() throws Exception {
        MonitoringFeatureSet featureSet = new MonitoringFeatureSet(Settings.EMPTY, licenseState, exporters, namedWriteableRegistry);
        boolean available = randomBoolean();
        when(licenseState.isMonitoringAllowed()).thenReturn(available);
        assertThat(featureSet.available(), is(available));
    }

    public void testEnabledSetting() throws Exception {
        boolean enabled = randomBoolean();
        Settings.Builder settings = Settings.builder();
        settings.put("xpack.monitoring.enabled", enabled);
        MonitoringFeatureSet featureSet = new MonitoringFeatureSet(settings.build(), licenseState, exporters, namedWriteableRegistry);
        assertThat(featureSet.enabled(), is(enabled));
    }

    public void testEnabledDefault() throws Exception {
        MonitoringFeatureSet featureSet = new MonitoringFeatureSet(Settings.EMPTY, licenseState, exporters, namedWriteableRegistry);
        assertThat(featureSet.enabled(), is(true));
    }

    public void testUsage() throws Exception {

        List<Exporter> exporterList = new ArrayList<>();
        int localCount = randomIntBetween(0, 5);
        for (int i = 0; i < localCount; i++) {
            Exporter exporter = mockExporter(LocalExporter.TYPE, true);
            exporterList.add(exporter);
            if (randomBoolean()) {
                exporter = mockExporter(LocalExporter.TYPE, false);
                exporterList.add(exporter);
            }
        }
        int httpCount = randomIntBetween(0, 5);
        for (int i = 0; i < httpCount; i++) {
            Exporter exporter = mockExporter(HttpExporter.TYPE, true);
            exporterList.add(exporter);
            if (randomBoolean()) {
                exporter = mockExporter(HttpExporter.TYPE, false);
                exporterList.add(exporter);
            }
        }
        int xCount = randomIntBetween(0, 5);
        String xType = randomAsciiOfLength(10);
        for (int i = 0; i < xCount; i++) {
            Exporter exporter = mockExporter(xType, true);
            exporterList.add(exporter);
            if (randomBoolean()) {
                exporter = mockExporter(xType, false);
                exporterList.add(exporter);
            }
        }
        when(exporters.iterator()).thenReturn(exporterList.iterator());

        MonitoringFeatureSet featureSet = new MonitoringFeatureSet(Settings.EMPTY, licenseState, exporters, namedWriteableRegistry);
        XPackFeatureSet.Usage usage = featureSet.usage();
        assertThat(usage.name(), is(featureSet.name()));
        assertThat(usage.enabled(), is(featureSet.enabled()));
        XContentSource source = new XContentSource(usage);
        assertThat(source.getValue("enabled_exporters"), is(notNullValue()));
        if (localCount > 0) {
            assertThat(source.getValue("enabled_exporters.local"), is(localCount));
        } else {
            assertThat(source.getValue("enabled_exporters.local"), is(nullValue()));
        }
        if (httpCount > 0) {
            assertThat(source.getValue("enabled_exporters.http"), is(httpCount));
        } else {
            assertThat(source.getValue("enabled_exporters.http"), is(nullValue()));
        }
        if (xCount > 0) {
            assertThat(source.getValue("enabled_exporters." + xType), is(xCount));
        } else {
            assertThat(source.getValue("enabled_exporters." + xType), is(nullValue()));
        }
    }

    private Exporter mockExporter(String type, boolean enabled) {
        Exporter exporter = mock(Exporter.class);
        when(exporter.type()).thenReturn(type);
        Exporter.Config enabledConfig = mock(Exporter.Config.class);
        when(enabledConfig.enabled()).thenReturn(enabled);
        when(exporter.config()).thenReturn(enabledConfig);
        return exporter;
    }
}
