/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.integration;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.routing.UnassignedInfo;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.XPackSingleNodeTestCase;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.MlMetaIndex;
import org.elasticsearch.xpack.ml.action.PutJobAction;
import org.elasticsearch.xpack.ml.calendars.SpecialEvent;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.Connective;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.DetectionRule;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.MlFilter;
import org.elasticsearch.xpack.ml.job.config.RuleAction;
import org.elasticsearch.xpack.ml.job.config.RuleCondition;
import org.elasticsearch.xpack.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.ml.job.persistence.JobDataCountsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.AutodetectParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.DataCounts;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.DataCountsTests;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSizeStats;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.Quantiles;
import org.junit.Before;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;


public class JobProviderIT extends XPackSingleNodeTestCase {

    private JobProvider jobProvider;

    @Override
    protected Settings nodeSettings()  {
        Settings.Builder newSettings = Settings.builder();
        newSettings.put(super.nodeSettings());
        newSettings.put(XPackSettings.SECURITY_ENABLED.getKey(), false);
        newSettings.put(XPackSettings.MONITORING_ENABLED.getKey(), false);
        newSettings.put(XPackSettings.WATCHER_ENABLED.getKey(), false);
        return newSettings.build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        return pluginList(XPackPlugin.class);
    }

    @Before
    public void createComponents() throws Exception {
        Settings.Builder builder = Settings.builder()
                .put(UnassignedInfo.INDEX_DELAYED_NODE_LEFT_TIMEOUT_SETTING.getKey(), TimeValue.timeValueSeconds(1));
        jobProvider = new JobProvider(client(), builder.build());
        waitForMlTemplates();
    }

    private void waitForMlTemplates() throws Exception {
        // block until the templates are installed
        assertBusy(() -> {
            ClusterState state = client().admin().cluster().prepareState().get().getState();
            assertTrue("Timed out waiting for the ML templates to be installed",
                    MachineLearning.allTemplatesInstalled(state));
        });
    }

    public void testSpecialEvents() throws Exception {
        List<SpecialEvent> events = new ArrayList<>();
        events.add(new SpecialEvent("A_and_B_downtime", "downtime", createZonedDateTime(1000L), createZonedDateTime(2000L),
                        Arrays.asList("job_a", "job_b")));
        events.add(new SpecialEvent("A_downtime", "downtime", createZonedDateTime(5000L), createZonedDateTime(10000L),
                        Collections.singletonList("job_a")));
        indexSpecialEvents(events);


        Job.Builder job = createJob("job_b");
        List<SpecialEvent> returnedEvents = getSpecialEvents(job.getId());
        assertEquals(1, returnedEvents.size());
        assertEquals(events.get(0), returnedEvents.get(0));

        job = createJob("job_a");
        returnedEvents = getSpecialEvents(job.getId());
        assertEquals(2, returnedEvents.size());
        assertEquals(events.get(0), returnedEvents.get(0));
        assertEquals(events.get(1), returnedEvents.get(1));

        job = createJob("job_c");
        returnedEvents = getSpecialEvents(job.getId());
        assertEquals(0, returnedEvents.size());
    }

    public void testGetAutodetectParams() throws Exception {
        String jobId = "test_get_autodetect_params";
        Job.Builder job = createJob(jobId, Arrays.asList("fruit", "tea"));

        // index the param docs
        List<SpecialEvent> events = new ArrayList<>();
        events.add(new SpecialEvent("A_downtime", "downtime", createZonedDateTime(5000L), createZonedDateTime(10000L),
                Collections.singletonList(jobId)));
        events.add(new SpecialEvent("A_downtime2", "downtime", createZonedDateTime(20000L), createZonedDateTime(21000L),
                Collections.singletonList(jobId)));
        indexSpecialEvents(events);

        List<MlFilter> filters = new ArrayList<>();
        filters.add(new MlFilter("fruit", Arrays.asList("apple", "pear")));
        filters.add(new MlFilter("tea", Arrays.asList("green", "builders")));
        indexFilters(filters);

        DataCounts earliestCounts = DataCountsTests.createTestInstance(jobId);
        earliestCounts.setLatestRecordTimeStamp(new Date(1500000000000L));
        indexDataCounts(earliestCounts, jobId);
        DataCounts latestCounts = DataCountsTests.createTestInstance(jobId);
        latestCounts.setLatestRecordTimeStamp(new Date(1510000000000L));
        indexDataCounts(latestCounts, jobId);

        ModelSizeStats earliestSizeStats = new ModelSizeStats.Builder(jobId).setLogTime(new Date(1500000000000L)).build();
        ModelSizeStats latestSizeStats = new ModelSizeStats.Builder(jobId).setLogTime(new Date(1510000000000L)).build();
        indexModelSizeStats(earliestSizeStats);
        indexModelSizeStats(latestSizeStats);

        job.setModelSnapshotId("snap_1");
        ModelSnapshot snapshot = new ModelSnapshot.Builder(jobId).setSnapshotId("snap_1").build();
        indexModelSnapshot(snapshot);

        Quantiles quantiles = new Quantiles(jobId, new Date(), "quantile-state");
        indexQuantiles(quantiles);

        client().admin().indices().prepareRefresh(MlMetaIndex.INDEX_NAME, AnomalyDetectorsIndex.jobStateIndexName(),
                AnomalyDetectorsIndex.jobResultsAliasedName(jobId)).get();


        AutodetectParams params = getAutodetectParams(job.build(new Date()));

        // special events
        assertNotNull(params.specialEvents());
        assertEquals(2, params.specialEvents().size());
        assertEquals(events.get(0), params.specialEvents().get(0));
        assertEquals(events.get(1), params.specialEvents().get(1));

        // filters
        assertNotNull(params.filters());
        assertEquals(2, params.filters().size());
        assertTrue(params.filters().contains(filters.get(0)));
        assertTrue(params.filters().contains(filters.get(1)));

        // datacounts
        assertNotNull(params.dataCounts());
        assertEquals(latestCounts, params.dataCounts());

        // model size stats
        assertNotNull(params.modelSizeStats());
        assertEquals(latestSizeStats, params.modelSizeStats());

        // model snapshot
        assertNotNull(params.modelSnapshot());
        assertEquals(snapshot, params.modelSnapshot());

        // quantiles
        assertNotNull(params.quantiles());
        assertEquals(quantiles, params.quantiles());
    }

    private AutodetectParams getAutodetectParams(Job job) throws Exception {
        AtomicReference<Exception> errorHolder = new AtomicReference<>();
        AtomicReference<AutodetectParams> searchResultHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        jobProvider.getAutodetectParams(job, params -> {
            searchResultHolder.set(params);
            latch.countDown();
        }, e -> {
            errorHolder.set(e);
            latch.countDown();
        });

        latch.await();
        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }

        return searchResultHolder.get();
    }

    private List<SpecialEvent> getSpecialEvents(String jobId) throws Exception {
        AtomicReference<Exception> errorHolder = new AtomicReference<>();
        AtomicReference<List<SpecialEvent>> searchResultHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        jobProvider.specialEvents(jobId, params -> {
            searchResultHolder.set(params);
            latch.countDown();
        }, e -> {
            errorHolder.set(e);
            latch.countDown();
        });

        latch.await();
        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }

        return searchResultHolder.get();
    }

    private Job.Builder createJob(String jobId) {
        return createJob(jobId, Collections.emptyList());
    }

    private Job.Builder createJob(String jobId, List<String> filterIds) {
        Job.Builder builder = new Job.Builder(jobId);
        AnalysisConfig.Builder ac = createAnalysisConfig(filterIds);
        DataDescription.Builder dc = new DataDescription.Builder();
        builder.setAnalysisConfig(ac);
        builder.setDataDescription(dc);

        PutJobAction.Request request = new PutJobAction.Request(builder);
        PutJobAction.Response response = client().execute(PutJobAction.INSTANCE, request).actionGet();
        assertTrue(response.isAcknowledged());
        return builder;
    }

    private AnalysisConfig.Builder createAnalysisConfig(List<String> filterIds) {
        Detector.Builder detector = new Detector.Builder("mean", "field");
        detector.setByFieldName("by_field");

        if (!filterIds.isEmpty()) {
            List<RuleCondition> conditions = new ArrayList<>();

            for (String filterId : filterIds) {
                conditions.add(RuleCondition.createCategorical("by_field", filterId));
            }

            DetectionRule.Builder rule = new DetectionRule.Builder(conditions)
                    .setRuleAction(RuleAction.FILTER_RESULTS)
                    .setConditionsConnective(Connective.OR);

            detector.setDetectorRules(Collections.singletonList(rule.build()));
        }

        return new AnalysisConfig.Builder(Collections.singletonList(detector.build()));
    }

    private void indexSpecialEvents(List<SpecialEvent> events) throws IOException {
        BulkRequestBuilder bulkRequest = client().prepareBulk();
        bulkRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        for (SpecialEvent event : events) {
            IndexRequest indexRequest = new IndexRequest(MlMetaIndex.INDEX_NAME, MlMetaIndex.TYPE, event.documentId());
            try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
                ToXContent.MapParams params = new ToXContent.MapParams(Collections.singletonMap(MlMetaIndex.INCLUDE_TYPE_KEY, "true"));
                indexRequest.source(event.toXContent(builder, params));
                bulkRequest.add(indexRequest);
            }
        }
        BulkResponse response = bulkRequest.execute().actionGet();
        if (response.hasFailures()) {
            throw new IllegalStateException(Strings.toString(response));
        }
    }

    private void indexDataCounts(DataCounts counts, String jobId) throws Exception {
        JobDataCountsPersister persister = new JobDataCountsPersister(nodeSettings(), client());

        AtomicReference<Exception> errorHolder = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        persister.persistDataCounts(jobId, counts, new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean aBoolean) {
                assertTrue(aBoolean);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                errorHolder.set(e);
                latch.countDown();
            }
        });

        latch.await();
        if (errorHolder.get() != null) {
            throw errorHolder.get();
        }
    }

    private void indexFilters(List<MlFilter> filters) throws IOException {
        BulkRequestBuilder bulkRequest = client().prepareBulk();
        bulkRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        for (MlFilter filter : filters) {
            IndexRequest indexRequest = new IndexRequest(MlMetaIndex.INDEX_NAME, MlMetaIndex.TYPE, filter.documentId());
            try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
                indexRequest.source(filter.toXContent(builder, ToXContent.EMPTY_PARAMS));
                bulkRequest.add(indexRequest);
            }
        }
        bulkRequest.execute().actionGet();
    }

    private void indexModelSizeStats(ModelSizeStats modelSizeStats) {
        JobResultsPersister persister = new JobResultsPersister(nodeSettings(), client());
        persister.persistModelSizeStats(modelSizeStats);
    }

    private void indexModelSnapshot(ModelSnapshot snapshot) {
        JobResultsPersister persister = new JobResultsPersister(nodeSettings(), client());
        persister.persistModelSnapshot(snapshot, WriteRequest.RefreshPolicy.IMMEDIATE);
    }

    private void indexQuantiles(Quantiles quantiles) {
        JobResultsPersister persister = new JobResultsPersister(nodeSettings(), client());
        persister.persistQuantiles(quantiles);

    }

    private ZonedDateTime createZonedDateTime(long epochMs) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneOffset.UTC);
    }
}
