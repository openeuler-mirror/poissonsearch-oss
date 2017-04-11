/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.datafeed;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.mock.orig.Mockito;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.action.FlushJobAction;
import org.elasticsearch.xpack.ml.action.OpenJobAction;
import org.elasticsearch.xpack.ml.action.PostDataAction;
import org.elasticsearch.xpack.ml.action.StartDatafeedAction;
import org.elasticsearch.xpack.ml.action.StartDatafeedAction.DatafeedTask;
import org.elasticsearch.xpack.ml.action.StartDatafeedActionTests;
import org.elasticsearch.xpack.ml.datafeed.extractor.DataExtractor;
import org.elasticsearch.xpack.ml.datafeed.extractor.DataExtractorFactory;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobState;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.MockClientBuilder;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.DataCounts;
import org.elasticsearch.xpack.ml.notifications.AuditMessage;
import org.elasticsearch.xpack.ml.notifications.Auditor;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.PersistentTask;
import org.elasticsearch.xpack.persistent.PersistentTasksService;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;

import static org.elasticsearch.xpack.ml.action.OpenJobActionTests.createJobTask;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class DatafeedManagerTests extends ESTestCase {

    private Client client;
    private ActionFuture<PostDataAction.Response> jobDataFuture;
    private ActionFuture<FlushJobAction.Response> flushJobFuture;
    private ClusterService clusterService;
    private ThreadPool threadPool;
    private DataExtractorFactory dataExtractorFactory;
    private DatafeedManager datafeedManager;
    private long currentTime = 120000;
    private Auditor auditor;

    @Before
    @SuppressWarnings("unchecked")
    public void setUpTests() {
        MlMetadata.Builder mlMetadata = new MlMetadata.Builder();
        Job job = createDatafeedJob().build(new Date());
        mlMetadata.putJob(job, false);
        mlMetadata.putDatafeed(createDatafeedConfig("datafeed_id", job.getId()).build());
        PersistentTask<OpenJobAction.Request> task = createJobTask(0L, job.getId(), "node_id", JobState.OPENED);
        PersistentTasksCustomMetaData tasks = new PersistentTasksCustomMetaData(1L, Collections.singletonMap(0L, task));
        DiscoveryNodes nodes = DiscoveryNodes.builder()
                .add(new DiscoveryNode("node_name", "node_id", new TransportAddress(InetAddress.getLoopbackAddress(), 9300),
                        Collections.emptyMap(), Collections.emptySet(), Version.CURRENT))
                .build();
        ClusterState.Builder cs = ClusterState.builder(new ClusterName("cluster_name"))
                .metaData(new MetaData.Builder().putCustom(MlMetadata.TYPE, mlMetadata.build())
                        .putCustom(PersistentTasksCustomMetaData.TYPE, tasks))
                .nodes(nodes);

        clusterService = mock(ClusterService.class);
        when(clusterService.state()).thenReturn(cs.build());


        ArgumentCaptor<XContentBuilder> argumentCaptor = ArgumentCaptor.forClass(XContentBuilder.class);
        client = new MockClientBuilder("foo")
                .prepareIndex(Auditor.NOTIFICATIONS_INDEX, AuditMessage.TYPE.getPreferredName(), "responseId", argumentCaptor)
                .build();

        jobDataFuture = mock(ActionFuture.class);
        flushJobFuture = mock(ActionFuture.class);
        DiscoveryNode dNode = mock(DiscoveryNode.class);
        when(dNode.getName()).thenReturn("this_node_has_a_name");
        when(clusterService.localNode()).thenReturn(dNode);
        auditor = mock(Auditor.class);

        JobProvider jobProvider = mock(JobProvider.class);
        Mockito.doAnswer(invocationOnMock -> {
            String jobId = (String) invocationOnMock.getArguments()[0];
            @SuppressWarnings("unchecked")
            Consumer<DataCounts> handler = (Consumer<DataCounts>) invocationOnMock.getArguments()[1];
            handler.accept(new DataCounts(jobId));
            return null;
        }).when(jobProvider).dataCounts(any(), any(), any());
        dataExtractorFactory = mock(DataExtractorFactory.class);
        auditor = mock(Auditor.class);
        threadPool = mock(ThreadPool.class);
        ExecutorService executorService = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArguments()[0]).run();
            return null;
        }).when(executorService).submit(any(Runnable.class));
        when(threadPool.executor(MachineLearning.DATAFEED_THREAD_POOL_NAME)).thenReturn(executorService);
        when(threadPool.executor(ThreadPool.Names.GENERIC)).thenReturn(executorService);
        when(client.execute(same(PostDataAction.INSTANCE), any())).thenReturn(jobDataFuture);
        when(client.execute(same(FlushJobAction.INSTANCE), any())).thenReturn(flushJobFuture);

        PersistentTasksService persistentTasksService = mock(PersistentTasksService.class);
        datafeedManager = new DatafeedManager(threadPool, client, clusterService, jobProvider, () -> currentTime, auditor,
                persistentTasksService) {
            @Override
            DataExtractorFactory createDataExtractorFactory(DatafeedConfig datafeedConfig, Job job) {
                return dataExtractorFactory;
            }
        };

        doAnswer(invocationOnMock -> {
            @SuppressWarnings("rawtypes")
            Consumer consumer = (Consumer) invocationOnMock.getArguments()[3];
            consumer.accept(new ResourceNotFoundException("dummy"));
            return null;
        }).when(jobProvider).bucketsViaInternalClient(any(), any(), any(), any());
    }

    public void testLookbackOnly_WarnsWhenNoDataIsRetrieved() throws Exception {
        DataExtractor dataExtractor = mock(DataExtractor.class);
        when(dataExtractorFactory.newExtractor(0L, 60000L)).thenReturn(dataExtractor);
        when(dataExtractor.hasNext()).thenReturn(true).thenReturn(false);
        when(dataExtractor.next()).thenReturn(Optional.empty());
        Consumer<Exception> handler = mockConsumer();
        DatafeedTask task = createDatafeedTask("datafeed_id", 0L, 60000L);
        datafeedManager.run(task, handler);

        verify(threadPool, times(1)).executor(MachineLearning.DATAFEED_THREAD_POOL_NAME);
        verify(threadPool, never()).schedule(any(), any(), any());
        verify(client, never()).execute(same(PostDataAction.INSTANCE), eq(new PostDataAction.Request("foo")));
        verify(client, never()).execute(same(FlushJobAction.INSTANCE), any());
        verify(auditor).warning("job_id", "Datafeed lookback retrieved no data");
    }

    public void testStart_GivenNewlyCreatedJobLoopBack() throws Exception {
        DataExtractor dataExtractor = mock(DataExtractor.class);
        when(dataExtractorFactory.newExtractor(0L, 60000L)).thenReturn(dataExtractor);
        when(dataExtractor.hasNext()).thenReturn(true).thenReturn(false);
        byte[] contentBytes = "".getBytes(Charset.forName("utf-8"));
        XContentType xContentType = XContentType.JSON;
        InputStream in = new ByteArrayInputStream(contentBytes);
        when(dataExtractor.next()).thenReturn(Optional.of(in));
        DataCounts dataCounts = new DataCounts("job_id", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new Date(0), new Date(0), new Date(0), new Date(0), new Date(0));
        when(jobDataFuture.actionGet()).thenReturn(new PostDataAction.Response(dataCounts));
        Consumer<Exception> handler = mockConsumer();
        DatafeedTask task = createDatafeedTask("datafeed_id", 0L, 60000L);
        datafeedManager.run(task, handler);

        verify(threadPool, times(1)).executor(MachineLearning.DATAFEED_THREAD_POOL_NAME);
        verify(threadPool, never()).schedule(any(), any(), any());
        verify(client).execute(same(PostDataAction.INSTANCE),
                eq(createExpectedPostDataRequest("job_id", contentBytes, xContentType)));
        verify(client).execute(same(FlushJobAction.INSTANCE), any());
    }

    private static PostDataAction.Request createExpectedPostDataRequest(String jobId,
            byte[] contentBytes, XContentType xContentType) {
        DataDescription.Builder expectedDataDescription = new DataDescription.Builder();
        expectedDataDescription.setTimeFormat("epoch_ms");
        expectedDataDescription.setFormat(DataDescription.DataFormat.XCONTENT);
        PostDataAction.Request expectedPostDataRequest = new PostDataAction.Request(jobId);
        expectedPostDataRequest.setDataDescription(expectedDataDescription.build());
        expectedPostDataRequest.setContent(new BytesArray(contentBytes), xContentType);
        return expectedPostDataRequest;
    }

    public void testStart_extractionProblem() throws Exception {
        DataExtractor dataExtractor = mock(DataExtractor.class);
        when(dataExtractorFactory.newExtractor(0L, 60000L)).thenReturn(dataExtractor);
        when(dataExtractor.hasNext()).thenReturn(true).thenReturn(false);
        when(dataExtractor.next()).thenThrow(new RuntimeException("dummy"));
        DataCounts dataCounts = new DataCounts("job_id", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new Date(0), new Date(0), new Date(0), new Date(0), new Date(0));
        when(jobDataFuture.actionGet()).thenReturn(new PostDataAction.Response(dataCounts));
        Consumer<Exception> handler = mockConsumer();
        DatafeedTask task = createDatafeedTask("datafeed_id", 0L, 60000L);
        datafeedManager.run(task, handler);

        verify(threadPool, times(1)).executor(MachineLearning.DATAFEED_THREAD_POOL_NAME);
        verify(threadPool, never()).schedule(any(), any(), any());
        verify(client, never()).execute(same(PostDataAction.INSTANCE), eq(new PostDataAction.Request("foo")));
        verify(client, never()).execute(same(FlushJobAction.INSTANCE), any());
    }

    public void testStart_emptyDataCountException() throws Exception {
        currentTime = 6000000;
        Job.Builder jobBuilder = createDatafeedJob();
        DatafeedConfig datafeedConfig = createDatafeedConfig("datafeed1", "job_id").build();
        Job job = jobBuilder.build(new Date());
        MlMetadata mlMetadata = new MlMetadata.Builder()
                .putJob(job, false)
                .putDatafeed(datafeedConfig)
                .build();
        when(clusterService.state()).thenReturn(ClusterState.builder(new ClusterName("_name"))
                .metaData(MetaData.builder().putCustom(MlMetadata.TYPE, mlMetadata))
                .build());
        int[] counter = new int[] {0};
        doAnswer(invocationOnMock -> {
            if (counter[0]++ < 10) {
                Runnable r = (Runnable) invocationOnMock.getArguments()[2];
                currentTime += 600000;
                r.run();
            }
            return mock(ScheduledFuture.class);
        }).when(threadPool).schedule(any(), any(), any());

        DataExtractor dataExtractor = mock(DataExtractor.class);
        when(dataExtractorFactory.newExtractor(anyLong(), anyLong())).thenReturn(dataExtractor);
        when(dataExtractor.hasNext()).thenReturn(false);
        Consumer<Exception> handler = mockConsumer();
        DatafeedTask task = createDatafeedTask("datafeed_id", 0L, null);
        DatafeedManager.Holder holder = datafeedManager.createJobDatafeed(datafeedConfig, job, 100, 100, handler, task);
        datafeedManager.doDatafeedRealtime(10L, "foo", holder);

        verify(threadPool, times(11)).schedule(any(), eq(MachineLearning.DATAFEED_THREAD_POOL_NAME), any());
        verify(auditor, times(1)).warning(eq("job_id"), anyString());
        verify(client, never()).execute(same(PostDataAction.INSTANCE), any());
        verify(client, never()).execute(same(FlushJobAction.INSTANCE), any());
    }

    public void testRealTime_GivenPostAnalysisProblemIsConflict() throws Exception {
        Exception conflictProblem = ExceptionsHelper.conflictStatusException("conflict");
        when(client.execute(same(PostDataAction.INSTANCE), any())).thenThrow(conflictProblem);

        DataExtractor dataExtractor = mock(DataExtractor.class);
        when(dataExtractorFactory.newExtractor(anyLong(), anyLong())).thenReturn(dataExtractor);
        when(dataExtractor.hasNext()).thenReturn(true).thenReturn(false);
        byte[] contentBytes = "".getBytes(Charset.forName("utf-8"));
        InputStream in = new ByteArrayInputStream(contentBytes);
        when(dataExtractor.next()).thenReturn(Optional.of(in));

        DataCounts dataCounts = new DataCounts("job_id", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new Date(0), new Date(0), new Date(0), new Date(0), new Date(0));
        when(jobDataFuture.actionGet()).thenReturn(new PostDataAction.Response(dataCounts));
        Consumer<Exception> handler = mockConsumer();
        StartDatafeedAction.Request startDatafeedRequest = new StartDatafeedAction.Request("datafeed_id", 0L);
        DatafeedTask task = StartDatafeedActionTests.createDatafeedTask(1, "type", "action", null,
                startDatafeedRequest, datafeedManager);
        task = spyDatafeedTask(task);
        datafeedManager.run(task, handler);

        ArgumentCaptor<DatafeedJob.AnalysisProblemException> analysisProblemCaptor =
                ArgumentCaptor.forClass(DatafeedJob.AnalysisProblemException.class);
        verify(handler).accept(analysisProblemCaptor.capture());
        assertThat(analysisProblemCaptor.getValue().getCause(), equalTo(conflictProblem));
        verify(auditor).error("job_id", "Datafeed is encountering errors submitting data for analysis: conflict");
        assertThat(datafeedManager.isRunning(task.getDatafeedId()), is(false));
    }

    public void testRealTime_GivenPostAnalysisProblemIsNonConflict() throws Exception {
        Exception nonConflictProblem = new RuntimeException("just runtime");
        when(client.execute(same(PostDataAction.INSTANCE), any())).thenThrow(nonConflictProblem);

        DataExtractor dataExtractor = mock(DataExtractor.class);
        when(dataExtractorFactory.newExtractor(anyLong(), anyLong())).thenReturn(dataExtractor);
        when(dataExtractor.hasNext()).thenReturn(true).thenReturn(false);
        byte[] contentBytes = "".getBytes(Charset.forName("utf-8"));
        InputStream in = new ByteArrayInputStream(contentBytes);
        when(dataExtractor.next()).thenReturn(Optional.of(in));

        DataCounts dataCounts = new DataCounts("job_id", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new Date(0), new Date(0), new Date(0), new Date(0), new Date(0));
        when(jobDataFuture.actionGet()).thenReturn(new PostDataAction.Response(dataCounts));
        Consumer<Exception> handler = mockConsumer();
        StartDatafeedAction.Request startDatafeedRequest = new StartDatafeedAction.Request("datafeed_id", 0L);
        DatafeedTask task = StartDatafeedActionTests.createDatafeedTask(1, "type", "action", null,
                startDatafeedRequest, datafeedManager);
        task = spyDatafeedTask(task);
        datafeedManager.run(task, handler);

        verify(auditor).error("job_id", "Datafeed is encountering errors submitting data for analysis: just runtime");
        assertThat(datafeedManager.isRunning(task.getDatafeedId()), is(true));
    }

    public void testStart_GivenNewlyCreatedJobLoopBackAndRealtime() throws Exception {
        DataExtractor dataExtractor = mock(DataExtractor.class);
        when(dataExtractorFactory.newExtractor(0L, 60000L)).thenReturn(dataExtractor);
        when(dataExtractor.hasNext()).thenReturn(true).thenReturn(false);
        byte[] contentBytes = "".getBytes(Charset.forName("utf-8"));
        InputStream in = new ByteArrayInputStream(contentBytes);
        XContentType xContentType = XContentType.JSON;
        when(dataExtractor.next()).thenReturn(Optional.of(in));
        DataCounts dataCounts = new DataCounts("job_id", 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new Date(0), new Date(0), new Date(0), new Date(0), new Date(0));
        when(jobDataFuture.actionGet()).thenReturn(new PostDataAction.Response(dataCounts));
        Consumer<Exception> handler = mockConsumer();
        boolean cancelled = randomBoolean();
        StartDatafeedAction.Request startDatafeedRequest = new StartDatafeedAction.Request("datafeed_id", 0L);
        DatafeedTask task = StartDatafeedActionTests.createDatafeedTask(1, "type", "action", null,
                startDatafeedRequest, datafeedManager);
        task = spyDatafeedTask(task);
        datafeedManager.run(task, handler);

        verify(threadPool, times(1)).executor(MachineLearning.DATAFEED_THREAD_POOL_NAME);
        if (cancelled) {
            task.stop("test");
            verify(handler).accept(null);
            assertThat(datafeedManager.isRunning(task.getDatafeedId()), is(false));
        } else {
            verify(client).execute(same(PostDataAction.INSTANCE),
                    eq(createExpectedPostDataRequest("job_id", contentBytes, xContentType)));
            verify(client).execute(same(FlushJobAction.INSTANCE), any());
            verify(threadPool, times(1)).schedule(eq(new TimeValue(480100)), eq(MachineLearning.DATAFEED_THREAD_POOL_NAME), any());
            assertThat(datafeedManager.isRunning(task.getDatafeedId()), is(true));
        }
    }

    public static DatafeedConfig.Builder createDatafeedConfig(String datafeedId, String jobId) {
        DatafeedConfig.Builder datafeedConfig = new DatafeedConfig.Builder(datafeedId, jobId);
        datafeedConfig.setIndexes(Arrays.asList("myIndex"));
        datafeedConfig.setTypes(Arrays.asList("myType"));
        return datafeedConfig;
    }

    public static Job.Builder createDatafeedJob() {
        AnalysisConfig.Builder acBuilder = new AnalysisConfig.Builder(Arrays.asList(new Detector.Builder("metric", "field").build()));
        acBuilder.setBucketSpan(TimeValue.timeValueHours(1));
        acBuilder.setDetectors(Arrays.asList(new Detector.Builder("metric", "field").build()));

        Job.Builder builder = new Job.Builder("job_id");
        builder.setAnalysisConfig(acBuilder);
        return builder;
    }

    private static DatafeedTask createDatafeedTask(String datafeedId, long startTime, Long endTime) {
        DatafeedTask task = mock(DatafeedTask.class);
        when(task.getDatafeedId()).thenReturn(datafeedId);
        when(task.getDatafeedStartTime()).thenReturn(startTime);
        when(task.getEndTime()).thenReturn(endTime);
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("rawtypes")
            ActionListener listener = (ActionListener) invocationOnMock.getArguments()[1];
            listener.onResponse(mock(PersistentTask.class));
            return null;
        }).when(task).updatePersistentStatus(any(), any());
        return task;
    }

    @SuppressWarnings("unchecked")
    private Consumer<Exception> mockConsumer() {
        return mock(Consumer.class);
    }

    private DatafeedTask spyDatafeedTask(DatafeedTask task) {
        task = spy(task);
        doAnswer(invocationOnMock -> {
            @SuppressWarnings("rawtypes")
            ActionListener listener = (ActionListener) invocationOnMock.getArguments()[1];
            listener.onResponse(mock(PersistentTask.class));
            return null;
        }).when(task).updatePersistentStatus(any(), any());
        return task;
    }
}
