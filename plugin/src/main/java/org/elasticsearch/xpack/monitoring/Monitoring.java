/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.license.LicenseService;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.monitoring.action.MonitoringBulkAction;
import org.elasticsearch.xpack.monitoring.action.TransportMonitoringBulkAction;
import org.elasticsearch.xpack.monitoring.cleaner.CleanerService;
import org.elasticsearch.xpack.monitoring.collector.Collector;
import org.elasticsearch.xpack.monitoring.collector.cluster.ClusterStatsCollector;
import org.elasticsearch.xpack.monitoring.collector.indices.IndexRecoveryCollector;
import org.elasticsearch.xpack.monitoring.collector.indices.IndexStatsCollector;
import org.elasticsearch.xpack.monitoring.collector.ml.JobStatsCollector;
import org.elasticsearch.xpack.monitoring.collector.node.NodeStatsCollector;
import org.elasticsearch.xpack.monitoring.collector.shards.ShardsCollector;
import org.elasticsearch.xpack.monitoring.exporter.Exporter;
import org.elasticsearch.xpack.monitoring.exporter.Exporters;
import org.elasticsearch.xpack.monitoring.exporter.http.HttpExporter;
import org.elasticsearch.xpack.monitoring.exporter.local.LocalExporter;
import org.elasticsearch.xpack.monitoring.rest.action.RestMonitoringBulkAction;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.ssl.SSLService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.elasticsearch.common.settings.Setting.boolSetting;
import static org.elasticsearch.common.settings.Setting.timeSetting;

/**
 * This class activates/deactivates the monitoring modules depending if we're running a node client, transport client or tribe client:
 * - node clients: all modules are binded
 * - transport clients: only action/transport actions are binded
 * - tribe clients: everything is disables by default but can be enabled per tribe cluster
 */
public class Monitoring implements ActionPlugin {

    public static final String NAME = "monitoring";

    /**
     * The minimum amount of time allowed for the history duration.
     */
    public static final TimeValue HISTORY_DURATION_MINIMUM = TimeValue.timeValueHours(24);

    /**
     * The default retention duration of the monitoring history data.
     * <p>
     * Expected values:
     * <ul>
     * <li>Default: 7 days</li>
     * <li>Minimum: 1 day</li>
     * </ul>
     *
     * @see #HISTORY_DURATION_MINIMUM
     */
    public static final Setting<TimeValue> HISTORY_DURATION = timeSetting("xpack.monitoring.history.duration",
                                                                          TimeValue.timeValueHours(7 * 24), // default value (7 days)
                                                                          HISTORY_DURATION_MINIMUM,         // minimum value
                                                                          Setting.Property.Dynamic, Setting.Property.NodeScope);
    /**
     * The ability to automatically cleanup ".watcher_history*" indices while also cleaning up Monitoring indices.
     */
    public static final Setting<Boolean> CLEAN_WATCHER_HISTORY = boolSetting("xpack.watcher.history.cleaner_service.enabled",
                                                                             false,
                                                                             Setting.Property.Dynamic, Setting.Property.NodeScope);

    private final Settings settings;
    private final XPackLicenseState licenseState;
    private final boolean enabled;
    private final boolean transportClientMode;
    private final boolean tribeNode;

    public Monitoring(Settings settings, XPackLicenseState licenseState) {
        this.settings = settings;
        this.licenseState = licenseState;
        this.enabled = XPackSettings.MONITORING_ENABLED.get(settings);
        this.transportClientMode = XPackPlugin.transportClientMode(settings);
        this.tribeNode = XPackPlugin.isTribeNode(settings);
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isTransportClient() {
        return transportClientMode;
    }

    public Collection<Module> nodeModules() {
        List<Module> modules = new ArrayList<>();
        modules.add(b -> {
            XPackPlugin.bindFeatureSet(b, MonitoringFeatureSet.class);
            if (transportClientMode || enabled == false || tribeNode) {
                b.bind(Exporters.class).toProvider(Providers.of(null));
            }
        });
        return modules;
    }

    public Collection<Object> createComponents(InternalClient client, ThreadPool threadPool, ClusterService clusterService,
                                               LicenseService licenseService, SSLService sslService) {
        if (enabled == false || tribeNode) {
            return Collections.emptyList();
        }

        final ClusterSettings clusterSettings = clusterService.getClusterSettings();
        final CleanerService cleanerService = new CleanerService(settings, clusterSettings, threadPool, licenseState);
        final SSLService dynamicSSLService = sslService.createDynamicSSLService();

        Map<String, Exporter.Factory> exporterFactories = new HashMap<>();
        exporterFactories.put(HttpExporter.TYPE, config -> new HttpExporter(config, dynamicSSLService, threadPool.getThreadContext()));
        exporterFactories.put(LocalExporter.TYPE, config -> new LocalExporter(config, client, cleanerService));
        final Exporters exporters = new Exporters(settings, exporterFactories, clusterService, licenseState, threadPool.getThreadContext());

        Set<Collector> collectors = new HashSet<>();
        collectors.add(new IndexStatsCollector(settings, clusterService, licenseState, client));
        collectors.add(new ClusterStatsCollector(settings, clusterService, licenseState, client, licenseService));
        collectors.add(new ShardsCollector(settings, clusterService, licenseState));
        collectors.add(new NodeStatsCollector(settings, clusterService, licenseState, client));
        collectors.add(new IndexRecoveryCollector(settings, clusterService, licenseState, client));
        collectors.add(new JobStatsCollector(settings, clusterService, licenseState, client));

        final MonitoringService monitoringService = new MonitoringService(settings, clusterSettings, threadPool, collectors, exporters);

        return Arrays.asList(monitoringService, exporters, cleanerService);
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (false == enabled || tribeNode) {
            return emptyList();
        }
        return singletonList(new ActionHandler<>(MonitoringBulkAction.INSTANCE, TransportMonitoringBulkAction.class));
    }

    @Override
    public List<RestHandler> getRestHandlers(Settings settings, RestController restController, ClusterSettings clusterSettings,
            IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter, IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<DiscoveryNodes> nodesInCluster) {
        if (false == enabled || tribeNode) {
            return emptyList();
        }
        return singletonList(new RestMonitoringBulkAction(settings, restController));
    }

    public List<Setting<?>> getSettings() {
        return Collections.unmodifiableList(
                Arrays.asList(HISTORY_DURATION,
                              CLEAN_WATCHER_HISTORY,
                              MonitoringService.INTERVAL,
                              Exporters.EXPORTERS_SETTINGS,
                              Collector.INDICES,
                              ClusterStatsCollector.CLUSTER_STATS_TIMEOUT,
                              IndexRecoveryCollector.INDEX_RECOVERY_TIMEOUT,
                              IndexRecoveryCollector.INDEX_RECOVERY_ACTIVE_ONLY,
                              IndexStatsCollector.INDEX_STATS_TIMEOUT,
                              JobStatsCollector.JOB_STATS_TIMEOUT,
                              NodeStatsCollector.NODE_STATS_TIMEOUT)
        );
    }

    public List<String> getSettingsFilter() {
        final String exportersKey = Exporters.EXPORTERS_SETTINGS.getKey();
        return Collections.unmodifiableList(Arrays.asList(exportersKey + "*.auth.*", exportersKey + "*.ssl.*"));
    }

}
