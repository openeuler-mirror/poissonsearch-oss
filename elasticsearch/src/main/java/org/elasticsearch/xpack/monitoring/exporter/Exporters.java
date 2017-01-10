/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.monitoring.exporter.local.LocalExporter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptyMap;

public class Exporters extends AbstractLifecycleComponent implements Iterable<Exporter> {

    private final Map<String, Exporter.Factory> factories;
    private final AtomicReference<Map<String, Exporter>> exporters;

    public Exporters(Settings settings, Map<String, Exporter.Factory> factories, ClusterService clusterService) {
        super(settings);

        this.factories = factories;
        this.exporters = new AtomicReference<>(emptyMap());

        clusterService.getClusterSettings().addSettingsUpdateConsumer(MonitoringSettings.EXPORTERS_SETTINGS, this::setExportersSetting);
    }

    private void setExportersSetting(Settings exportersSetting) {
        if (this.lifecycleState() == Lifecycle.State.STARTED) {
            if (exportersSetting.names().isEmpty()) {
                return;
            }
            Map<String, Exporter> updated = initExporters(exportersSetting);
            closeExporters(logger, this.exporters.getAndSet(updated));
        }
    }

    @Override
    protected void doStart() {
        exporters.set(initExporters(MonitoringSettings.EXPORTERS_SETTINGS.get(settings)));
    }

    @Override
    protected void doStop() {
        closeExporters(logger, exporters.get());
    }

    @Override
    protected void doClose() {
    }

    public Exporter getExporter(String name) {
        return exporters.get().get(name);
    }

    @Override
    public Iterator<Exporter> iterator() {
        return exporters.get().values().iterator();
    }

    static void closeExporters(Logger logger, Map<String, Exporter> exporters) {
        for (Exporter exporter : exporters.values()) {
            try {
                exporter.close();
            } catch (Exception e) {
                logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to close exporter [{}]", exporter.name()), e);
            }
        }
    }

    ExportBulk openBulk() {
        List<ExportBulk> bulks = new ArrayList<>();
        for (Exporter exporter : this) {
            try {
                ExportBulk bulk = exporter.openBulk();
                if (bulk == null) {
                    logger.info("skipping exporter [{}] as it is not ready yet", exporter.name());
                } else {
                    bulks.add(bulk);
                }
            } catch (Exception e) {
                logger.error(
                        (Supplier<?>) () -> new ParameterizedMessage("exporter [{}] failed to open exporting bulk", exporter.name()), e);
            }
        }
        return bulks.isEmpty() ? null : new ExportBulk.Compound(bulks);
    }

    Map<String, Exporter> initExporters(Settings settings) {
        Set<String> singletons = new HashSet<>();
        Map<String, Exporter> exporters = new HashMap<>();
        boolean hasDisabled = false;
        for (String name : settings.names()) {
            Settings exporterSettings = settings.getAsSettings(name);
            String type = exporterSettings.get("type");
            if (type == null) {
                throw new SettingsException("missing exporter type for [" + name + "] exporter");
            }
            Exporter.Factory factory = factories.get(type);
            if (factory == null) {
                throw new SettingsException("unknown exporter type [" + type + "] set for exporter [" + name + "]");
            }
            Exporter.Config config = new Exporter.Config(name, type, exporterSettings);
            if (!config.enabled()) {
                hasDisabled = true;
                if (logger.isDebugEnabled()) {
                    logger.debug("exporter [{}/{}] is disabled", type, name);
                }
                continue;
            }
            Exporter exporter = factory.create(config);
            if (exporter.isSingleton()) {
                // this is a singleton exporter, let's make sure we didn't already create one
                // (there can only be one instance of a singleton exporter)
                if (singletons.contains(type)) {
                    throw new SettingsException("multiple [" + type + "] exporters are configured. there can " +
                            "only be one [" + type + "] exporter configured");
                }
                singletons.add(type);
            }
            exporters.put(config.name(), exporter);
        }

        // no exporters are configured, lets create a default local one.
        //
        // NOTE:    if there are exporters configured and they're all disabled, we don't
        //          fallback on the default
        //
        if (exporters.isEmpty() && !hasDisabled) {
            Exporter.Config config = new Exporter.Config("default_" + LocalExporter.TYPE, LocalExporter.TYPE, Settings.EMPTY);
            exporters.put(config.name(), factories.get(LocalExporter.TYPE).create(config));
        }

        return exporters;
    }

    /**
     * Exports a collection of monitoring documents using the configured exporters
     */
    public void export(Collection<MonitoringDoc> docs) throws ExportException {
        if (this.lifecycleState() != Lifecycle.State.STARTED) {
            throw new ExportException("Export service is not started");
        }
        if (docs != null && docs.size() > 0) {
            final ExportBulk bulk = openBulk();

            if (bulk != null) {
                try {
                    bulk.add(docs);
                } finally {
                    bulk.close(lifecycleState() == Lifecycle.State.STARTED);
                }
            }
        }
    }
}
