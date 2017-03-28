/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.support;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.indices.recovery.RecoveryState;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.discovery.TestZenDiscovery;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.MachineLearningTemplateRegistry;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.action.CloseJobAction;
import org.elasticsearch.xpack.ml.action.DeleteDatafeedAction;
import org.elasticsearch.xpack.ml.action.DeleteJobAction;
import org.elasticsearch.xpack.ml.action.GetDatafeedsStatsAction;
import org.elasticsearch.xpack.ml.action.GetJobsStatsAction;
import org.elasticsearch.xpack.ml.action.StopDatafeedAction;
import org.elasticsearch.xpack.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobState;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.DataCounts;
import org.junit.After;
import org.junit.Before;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

/**
 * A base class for testing datafeed and job lifecycle specifics.
 *
 * Note for other type of integration tests you should use the external test cluster created by the Gradle integTest task.
 * For example tests extending this base class test with the non native autodetect process.
 */
@ESIntegTestCase.ClusterScope(scope = ESIntegTestCase.Scope.TEST, numDataNodes = 0, numClientNodes = 0,
        transportClientRatio = 0, supportsDedicatedMasters = false)
public abstract class BaseMlIntegTestCase extends ESIntegTestCase {

    @Override
    protected boolean ignoreExternalCluster() {
        return true;
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        Settings.Builder settings = Settings.builder().put(super.nodeSettings(nodeOrdinal));
        settings.put(MachineLearning.AUTODETECT_PROCESS.getKey(), false);
        settings.put(XPackSettings.MACHINE_LEARNING_ENABLED.getKey(), true);
        settings.put(XPackSettings.SECURITY_ENABLED.getKey(), false);
        settings.put(XPackSettings.WATCHER_ENABLED.getKey(), false);
        settings.put(XPackSettings.MONITORING_ENABLED.getKey(), false);
        settings.put(XPackSettings.GRAPH_ENABLED.getKey(), false);
        return settings.build();
    }

    @Override
    protected Settings transportClientSettings() {
        Settings.Builder settings = Settings.builder().put(super.transportClientSettings());
        settings.put(XPackSettings.MACHINE_LEARNING_ENABLED.getKey(), true);
        settings.put(XPackSettings.SECURITY_ENABLED.getKey(), false);
        settings.put(XPackSettings.WATCHER_ENABLED.getKey(), false);
        settings.put(XPackSettings.MONITORING_ENABLED.getKey(), false);
        settings.put(XPackSettings.GRAPH_ENABLED.getKey(), false);
        return settings.build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Collections.singleton(XPackPlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();
    }

    @Override
    protected Collection<Class<? extends Plugin>> getMockPlugins() {
        return Arrays.asList(TestZenDiscovery.TestPlugin.class, TestSeedPlugin.class);
    }

    @Before
    public void ensureTemplatesArePresent() throws Exception {
        assertBusy(() -> {
            MetaData metaData = client().admin().cluster().prepareState().get().getState().getMetaData();
            assertTrue("Timed out waiting for the ML templates to be installed",
                    MachineLearningTemplateRegistry.allTemplatesInstalled(metaData));
        });
    }

    protected Job.Builder createJob(String id) {
        DataDescription.Builder dataDescription = new DataDescription.Builder();
        dataDescription.setFormat(DataDescription.DataFormat.XCONTENT);
        dataDescription.setTimeFormat(DataDescription.EPOCH_MS);

        Detector.Builder d = new Detector.Builder("count", null);
        AnalysisConfig.Builder analysisConfig = new AnalysisConfig.Builder(Collections.singletonList(d.build()));

        Job.Builder builder = new Job.Builder();
        builder.setId(id);
        builder.setCreateTime(new Date());

        builder.setAnalysisConfig(analysisConfig);
        builder.setDataDescription(dataDescription);
        return builder;
    }

    public static Job.Builder createFareQuoteJob(String id) {
        DataDescription.Builder dataDescription = new DataDescription.Builder();
        dataDescription.setFormat(DataDescription.DataFormat.XCONTENT);
        dataDescription.setTimeFormat(DataDescription.EPOCH);
        dataDescription.setTimeField("time");

        Detector.Builder d = new Detector.Builder("metric", "responsetime");
        d.setByFieldName("by_field_name");
        AnalysisConfig.Builder analysisConfig = new AnalysisConfig.Builder(Collections.singletonList(d.build()));
        analysisConfig.setBucketSpan(TimeValue.timeValueHours(1));

        Job.Builder builder = new Job.Builder();
        builder.setId(id);
        builder.setCreateTime(new Date());

        builder.setAnalysisConfig(analysisConfig);
        builder.setDataDescription(dataDescription);
        return builder;
    }

    public static Job.Builder createScheduledJob(String jobId) {
        DataDescription.Builder dataDescription = new DataDescription.Builder();
        dataDescription.setFormat(DataDescription.DataFormat.XCONTENT);
        dataDescription.setTimeFormat("yyyy-MM-dd HH:mm:ss");

        Detector.Builder d = new Detector.Builder("count", null);
        AnalysisConfig.Builder analysisConfig = new AnalysisConfig.Builder(Collections.singletonList(d.build()));

        Job.Builder builder = new Job.Builder();
        builder.setId(jobId);

        builder.setAnalysisConfig(analysisConfig);
        builder.setDataDescription(dataDescription);
        builder.setCreateTime(new Date());
        return builder;
    }

    public static DatafeedConfig createDatafeed(String datafeedId, String jobId, List<String> indexes) {
        return createDatafeedBuilder(datafeedId, jobId, indexes).build();
    }

    public static DatafeedConfig.Builder createDatafeedBuilder(String datafeedId, String jobId, List<String> indexes) {
        DatafeedConfig.Builder builder = new DatafeedConfig.Builder(datafeedId, jobId);
        builder.setQueryDelay(TimeValue.timeValueSeconds(1));
        builder.setFrequency(TimeValue.timeValueSeconds(2));
        builder.setIndexes(indexes);
        builder.setTypes(Collections.singletonList("type"));
        return builder;
    }

    @After
    public void cleanupWorkaround() throws Exception {
        logger.info("[{}#{}]: Cleaning up datafeeds and jobs after test", getTestClass().getSimpleName(), getTestName());
        deleteAllDatafeeds(logger, client());
        deleteAllJobs(logger, client());
        assertBusy(() -> {
            RecoveryResponse recoveryResponse = client().admin().indices().prepareRecoveries()
                    .setActiveOnly(true)
                    .get();
            for (List<RecoveryState> recoveryStates : recoveryResponse.shardRecoveryStates().values()) {
                assertThat(recoveryStates.size(), equalTo(0));
            }
        });
    }

    public static void indexDocs(Logger logger, String index, long numDocs, long start, long end) {
        int maxDelta = (int) (end - start - 1);
        BulkRequestBuilder bulkRequestBuilder = client().prepareBulk();
        for (int i = 0; i < numDocs; i++) {
            IndexRequest indexRequest = new IndexRequest(index, "type");
            long timestamp = start + randomIntBetween(0, maxDelta);
            assert timestamp >= start && timestamp < end;
            indexRequest.source("time", timestamp);
            bulkRequestBuilder.add(indexRequest);
        }
        BulkResponse bulkResponse = bulkRequestBuilder
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .get();
        assertThat(bulkResponse.hasFailures(), is(false));
        logger.info("Indexed [{}] documents", numDocs);
    }

    public static GetJobsStatsAction.Response.JobStats getJobStats(String jobId) {
        GetJobsStatsAction.Request request = new GetJobsStatsAction.Request(jobId);
        GetJobsStatsAction.Response response = client().execute(GetJobsStatsAction.INSTANCE, request).actionGet();
        if (response.getResponse().results().isEmpty()) {
            return null;
        } else {
            return response.getResponse().results().get(0);
        }
    }

    public static DataCounts getDataCounts(String jobId) {
        GetJobsStatsAction.Response.JobStats jobStats = getJobStats(jobId);
        if (jobStats != null) {
            return jobStats.getDataCounts();
        } else {
            return new DataCounts(jobId);
        }
    }

    public static GetDatafeedsStatsAction.Response.DatafeedStats getDatafeedStats(String datafeedId) {
        GetDatafeedsStatsAction.Request request = new GetDatafeedsStatsAction.Request(datafeedId);
        GetDatafeedsStatsAction.Response response = client().execute(GetDatafeedsStatsAction.INSTANCE, request).actionGet();
        if (response.getResponse().results().isEmpty()) {
            return null;
        } else {
            return response.getResponse().results().get(0);
        }
    }

    public static void deleteAllDatafeeds(Logger logger, Client client) throws Exception {
        MetaData metaData = client.admin().cluster().prepareState().get().getState().getMetaData();
        MlMetadata mlMetadata = metaData.custom(MlMetadata.TYPE);
        for (DatafeedConfig datafeed : mlMetadata.getDatafeeds().values()) {
            String datafeedId = datafeed.getId();
            try {
                StopDatafeedAction.Response stopResponse =
                        client.execute(StopDatafeedAction.INSTANCE, new StopDatafeedAction.Request(datafeedId)).get();
                assertTrue(stopResponse.isStopped());
            } catch (ExecutionException e1) {
                if (e1.getMessage().contains("Cannot stop datafeed [" + datafeedId + "] because it has already been stopped")) {
                    logger.debug("failed to stop datafeed [" + datafeedId + "], already stopped", e1);
                } else {
                    try {
                        StopDatafeedAction.Request request = new StopDatafeedAction.Request(datafeedId);
                        request.setForce(true);
                        StopDatafeedAction.Response stopResponse = client.execute(StopDatafeedAction.INSTANCE, request).get();
                        assertTrue(stopResponse.isStopped());
                    } catch (Exception e2) {
                        logger.warn("Force-stopping datafeed [" + datafeedId + "] failed.", e2);
                    }
                    throw new RuntimeException("Had to resort to force-stopping datafeed, something went wrong?", e1);
                }
            }
            assertBusy(() -> {
                try {
                    GetDatafeedsStatsAction.Request request = new GetDatafeedsStatsAction.Request(datafeedId);
                    GetDatafeedsStatsAction.Response r = client.execute(GetDatafeedsStatsAction.INSTANCE, request).get();
                    assertThat(r.getResponse().results().get(0).getDatafeedState(), equalTo(DatafeedState.STOPPED));
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            });
            DeleteDatafeedAction.Response deleteResponse =
                    client.execute(DeleteDatafeedAction.INSTANCE, new DeleteDatafeedAction.Request(datafeedId)).get();
            assertTrue(deleteResponse.isAcknowledged());
        }
    }

    public static void deleteAllJobs(Logger logger, Client client) throws Exception {
        MetaData metaData = client.admin().cluster().prepareState().get().getState().getMetaData();
        MlMetadata mlMetadata = metaData.custom(MlMetadata.TYPE);
        for (Map.Entry<String, Job> entry : mlMetadata.getJobs().entrySet()) {
            String jobId = entry.getKey();
            try {
                CloseJobAction.Request closeRequest = new CloseJobAction.Request(jobId);
                closeRequest.setTimeout(TimeValue.timeValueSeconds(30L));
                CloseJobAction.Response response =
                        client.execute(CloseJobAction.INSTANCE, closeRequest).get();
                assertTrue(response.isClosed());
            } catch (Exception e1) {
                if (e1.getMessage().contains("because job [" + jobId + "] hasn't been opened")) {
                    logger.debug("job [" + jobId + "] has already been closed", e1);
                } else {
                    try {
                        CloseJobAction.Request closeRequest = new CloseJobAction.Request(jobId);
                        closeRequest.setForce(true);
                        closeRequest.setTimeout(TimeValue.timeValueSeconds(30L));
                        CloseJobAction.Response response =
                                client.execute(CloseJobAction.INSTANCE, closeRequest).get();
                        assertTrue(response.isClosed());
                    } catch (Exception e2) {
                        logger.warn("Force-closing datafeed [" + jobId + "] failed.", e2);
                    }
                    throw new RuntimeException("Had to resort to force-closing job, something went wrong?", e1);
                }
            }
            assertBusy(() -> {
                GetJobsStatsAction.Response statsResponse =
                        client().execute(GetJobsStatsAction.INSTANCE, new GetJobsStatsAction.Request(jobId)).actionGet();
                assertEquals(JobState.CLOSED, statsResponse.getResponse().results().get(0).getState());
            });
            DeleteJobAction.Response response =
                    client.execute(DeleteJobAction.INSTANCE, new DeleteJobAction.Request(jobId)).get();
            assertTrue(response.isAcknowledged());
        }
    }

}
