/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.collector.ml;

import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.xpack.XPackClient;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.ml.action.GetJobsStatsAction;
import org.elasticsearch.xpack.ml.client.MachineLearningClient;
import org.elasticsearch.xpack.monitoring.collector.Collector;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringDoc;
import org.elasticsearch.xpack.security.InternalClient;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Collector for Machine Learning Job Stats.
 * <p>
 * This collector runs on the master node because it's the only dependable place that requires it when X-Pack ML is enabled.
 * If ML is not enabled or if it is not allowed to run because of the license, then this will not collect results.
 * <p>
 * Each Job Stats returned is used to create a separate {@link JobStatsMonitoringDoc}.
 */
public class JobStatsCollector extends Collector {

    /**
     * Timeout value when collecting ML job statistics (default to 10s)
     */
    public static final Setting<TimeValue> JOB_STATS_TIMEOUT = collectionTimeoutSetting("ml.job.stats.timeout");

    private final MachineLearningClient client;

    public JobStatsCollector(final Settings settings, final ClusterService clusterService,
                             final XPackLicenseState licenseState, final InternalClient client) {
        this(settings, clusterService, licenseState, new XPackClient(client).machineLearning());
    }

    JobStatsCollector(final Settings settings, final ClusterService clusterService,
                      final XPackLicenseState licenseState, final MachineLearningClient client) {
        super(settings, JobStatsMonitoringDoc.TYPE, clusterService, JOB_STATS_TIMEOUT, licenseState);

        this.client = client;
    }

    @Override
    protected boolean shouldCollect() {
        // This can only run when monitoring is allowed + ML is enabled/allowed, but also only on the elected master node
        return super.shouldCollect() &&
               XPackSettings.MACHINE_LEARNING_ENABLED.get(settings) && licenseState.isMachineLearningAllowed() &&
               isLocalNodeMaster();
    }

    @Override
    protected List<MonitoringDoc> doCollect(final MonitoringDoc.Node node, final long interval) throws Exception {
        // fetch details about all jobs
        final GetJobsStatsAction.Response jobs =
                client.getJobsStats(new GetJobsStatsAction.Request(MetaData.ALL))
                      .actionGet(getCollectionTimeout());

        final long timestamp = timestamp();
        final String clusterUuid = clusterUUID();

        return jobs.getResponse().results().stream()
                   .map(jobStats -> new JobStatsMonitoringDoc(clusterUuid, timestamp, interval, node, jobStats))
                   .collect(Collectors.toList());
    }

}
