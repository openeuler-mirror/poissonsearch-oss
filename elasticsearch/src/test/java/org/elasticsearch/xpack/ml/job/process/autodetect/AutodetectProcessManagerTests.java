/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.ml.MlPlugin;
import org.elasticsearch.xpack.ml.action.UpdateJobStatusAction;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobStatus;
import org.elasticsearch.xpack.ml.job.config.MlFilter;
import org.elasticsearch.xpack.ml.job.metadata.Allocation;
import org.elasticsearch.xpack.ml.job.persistence.JobDataCountsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobRenormalizedResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.AutodetectResultsParser;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.DataLoadParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.InterimResultsParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.TimeRange;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.DataCounts;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.process.normalizer.NormalizerFactory;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.Quantiles;
import org.elasticsearch.xpack.ml.job.results.AutodetectResult;
import org.junit.Before;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.elasticsearch.mock.orig.Mockito.doAnswer;
import static org.elasticsearch.mock.orig.Mockito.doReturn;
import static org.elasticsearch.mock.orig.Mockito.doThrow;
import static org.elasticsearch.mock.orig.Mockito.times;
import static org.elasticsearch.mock.orig.Mockito.verify;
import static org.elasticsearch.mock.orig.Mockito.when;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

/**
 * Calling the {@link AutodetectProcessManager#processData(String, InputStream, DataLoadParams)}
 * method causes an AutodetectCommunicator to be created on demand. Most of these tests have to
 * do that before they can assert other things
 */
public class AutodetectProcessManagerTests extends ESTestCase {

    private JobManager jobManager;
    private JobProvider jobProvider;
    private JobResultsPersister jobResultsPersister;
    private JobRenormalizedResultsPersister jobRenormalizedResultsPersister;
    private JobDataCountsPersister jobDataCountsPersister;
    private NormalizerFactory normalizerFactory;

    @Before
    public void initMocks() {
        jobManager = mock(JobManager.class);
        jobProvider = mock(JobProvider.class);
        jobResultsPersister = mock(JobResultsPersister.class);
        jobRenormalizedResultsPersister = mock(JobRenormalizedResultsPersister.class);
        jobDataCountsPersister = mock(JobDataCountsPersister.class);
        normalizerFactory = mock(NormalizerFactory.class);
        givenAllocationWithStatus(JobStatus.OPENED);
    }

    public void testOpenJob() {
        Client client = mock(Client.class);
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(createJobDetails("foo"));
        AutodetectProcessManager manager = createManager(communicator, client);

        manager.openJob("foo", false, e -> {});
        assertEquals(1, manager.numberOfOpenJobs());
        assertTrue(manager.jobHasActiveAutodetectProcess("foo"));
        UpdateJobStatusAction.Request expectedRequest = new UpdateJobStatusAction.Request("foo", JobStatus.OPENED);
        verify(client).execute(eq(UpdateJobStatusAction.INSTANCE), eq(expectedRequest), any());
    }

    public void testOpenJob_exceedMaxNumJobs() {
        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(createJobDetails("foo"));
        doAnswer(invocationOnMock -> {
            String jobId = (String) invocationOnMock.getArguments()[0];
            @SuppressWarnings("unchecked")
            Consumer<DataCounts> handler = (Consumer<DataCounts>) invocationOnMock.getArguments()[1];
            handler.accept(new DataCounts(jobId));
            return null;
        }).when(jobProvider).dataCounts(any(), any(), any());

        when(jobManager.getJobOrThrowIfUnknown("bar")).thenReturn(createJobDetails("bar"));
        when(jobManager.getJobOrThrowIfUnknown("baz")).thenReturn(createJobDetails("baz"));
        when(jobManager.getJobOrThrowIfUnknown("foobar")).thenReturn(createJobDetails("foobar"));

        Client client = mock(Client.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        ThreadPool.Cancellable cancellable = mock(ThreadPool.Cancellable.class);
        when(threadPool.scheduleWithFixedDelay(any(), any(), any())).thenReturn(cancellable);
        ExecutorService executorService = mock(ExecutorService.class);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArguments()[0]).run();
            return null;
        }).when(executorService).execute(any(Runnable.class));
        when(threadPool.executor(MlPlugin.AUTODETECT_PROCESS_THREAD_POOL_NAME)).thenReturn(executorService);
        AutodetectResultsParser parser = mock(AutodetectResultsParser.class);
        @SuppressWarnings("unchecked")
        Stream<AutodetectResult> stream = mock(Stream.class);
        @SuppressWarnings("unchecked")
        Iterator<AutodetectResult> iterator = mock(Iterator.class);
        when(stream.iterator()).thenReturn(iterator);
        when(iterator.hasNext()).thenReturn(false);
        when(parser.parseResults(any())).thenReturn(stream);
        AutodetectProcess autodetectProcess = mock(AutodetectProcess.class);
        when(autodetectProcess.isProcessAlive()).thenReturn(true);
        when(autodetectProcess.getPersistStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        AutodetectProcessFactory autodetectProcessFactory = (j, modelSnapshot, quantiles, filters, i, e) -> autodetectProcess;
        Settings.Builder settings = Settings.builder();
        settings.put(AutodetectProcessManager.MAX_RUNNING_JOBS_PER_NODE.getKey(), 3);
        AutodetectProcessManager manager = spy(new AutodetectProcessManager(settings.build(), client, threadPool, jobManager, jobProvider,
                jobResultsPersister, jobRenormalizedResultsPersister, jobDataCountsPersister, parser, autodetectProcessFactory,
                normalizerFactory));

        ModelSnapshot modelSnapshot = new ModelSnapshot("foo");
        Quantiles quantiles = new Quantiles("foo", new Date(), "state");
        Set<MlFilter> filters = new HashSet<>();
        doAnswer(invocationOnMock -> {
            AutodetectProcessManager.TriConsumer consumer = (AutodetectProcessManager.TriConsumer) invocationOnMock.getArguments()[1];
            consumer.accept(modelSnapshot, quantiles, filters);
            return null;
        }).when(manager).gatherRequiredInformation(any(), any(), any());

        manager.openJob("foo", false, e -> {});
        manager.openJob("bar", false, e -> {});
        manager.openJob("baz", false, e -> {});
        assertEquals(3, manager.numberOfOpenJobs());

        Exception e = expectThrows(ElasticsearchStatusException.class, () -> manager.openJob("foobar", false, e1 -> {}));
        assertEquals("max running job capacity [3] reached", e.getMessage());

        manager.closeJob("baz");
        assertEquals(2, manager.numberOfOpenJobs());
        manager.openJob("foobar", false, e1 -> {});
        assertEquals(3, manager.numberOfOpenJobs());
    }

    public void testProcessData()  {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);
        assertEquals(0, manager.numberOfOpenJobs());

        DataLoadParams params = new DataLoadParams(TimeRange.builder().build(), Optional.empty());
        manager.openJob("foo", false, e -> {});
        manager.processData("foo", createInputStream(""), params);
        assertEquals(1, manager.numberOfOpenJobs());
    }

    public void testProcessDataThrowsElasticsearchStatusException_onIoException() throws Exception {
        AutodetectCommunicator communicator = Mockito.mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);

        DataLoadParams params = mock(DataLoadParams.class);
        InputStream inputStream = createInputStream("");
        doThrow(new IOException("blah")).when(communicator).writeToJob(inputStream, params);

        manager.openJob("foo", false, e -> {});
        ESTestCase.expectThrows(ElasticsearchException.class,
                () -> manager.processData("foo", inputStream, params));
    }

    public void testCloseJob() {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(createJobDetails("foo"));
        AutodetectProcessManager manager = createManager(communicator);
        assertEquals(0, manager.numberOfOpenJobs());

        manager.openJob("foo", false, e -> {});
        manager.processData("foo", createInputStream(""), mock(DataLoadParams.class));

        // job is created
        assertEquals(1, manager.numberOfOpenJobs());
        manager.closeJob("foo");
        assertEquals(0, manager.numberOfOpenJobs());
    }

    public void testBucketResetMessageIsSent() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);

        DataLoadParams params = new DataLoadParams(TimeRange.builder().startTime("1000").endTime("2000").build(), Optional.empty());
        InputStream inputStream = createInputStream("");
        manager.openJob("foo", false, e -> {});
        manager.processData("foo", inputStream, params);
        verify(communicator).writeToJob(inputStream, params);
    }

    public void testFlush() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);
        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(createJobDetails("foo"));

        InputStream inputStream = createInputStream("");
        manager.openJob("foo", false, e -> {});
        manager.processData("foo", inputStream, mock(DataLoadParams.class));

        InterimResultsParams params = InterimResultsParams.builder().build();
        manager.flushJob("foo", params);

        verify(communicator).flushJob(params);
    }

    public void testFlushThrows() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManagerAndCallProcessData(communicator, "foo");

        InterimResultsParams params = InterimResultsParams.builder().build();
        doThrow(new IOException("blah")).when(communicator).flushJob(params);

        ElasticsearchException e = ESTestCase.expectThrows(ElasticsearchException.class, () -> manager.flushJob("foo", params));
        assertEquals("[foo] exception while flushing job", e.getMessage());
    }

    public void testWriteUpdateConfigMessage() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManagerAndCallProcessData(communicator, "foo");
        manager.writeUpdateConfigMessage("foo", "go faster");
        verify(communicator).writeUpdateConfigMessage("go faster");
    }

    public void testJobHasActiveAutodetectProcess() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);
        assertFalse(manager.jobHasActiveAutodetectProcess("foo"));

        manager.openJob("foo", false, e -> {});
        manager.processData("foo", createInputStream(""), mock(DataLoadParams.class));

        assertTrue(manager.jobHasActiveAutodetectProcess("foo"));
        assertFalse(manager.jobHasActiveAutodetectProcess("bar"));
    }

    public void testProcessData_GivenStatusNotStarted() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        when(communicator.writeToJob(any(), any())).thenReturn(new DataCounts("foo"));
        AutodetectProcessManager manager = createManager(communicator);

        Job job = createJobDetails("foo");

        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(job);
        givenAllocationWithStatus(JobStatus.OPENED);

        InputStream inputStream = createInputStream("");
        manager.openJob("foo", false, e -> {});
        DataCounts dataCounts = manager.processData("foo", inputStream, mock(DataLoadParams.class));

        assertThat(dataCounts, equalTo(new DataCounts("foo")));
    }

    public void testCreate_notEnoughThreads() throws IOException {
        Client client = mock(Client.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        ExecutorService executorService = mock(ExecutorService.class);
        doThrow(new EsRejectedExecutionException("")).when(executorService).execute(any());
        when(threadPool.executor(anyString())).thenReturn(executorService);
        when(jobManager.getJobOrThrowIfUnknown("my_id")).thenReturn(createJobDetails("my_id"));
        doAnswer(invocationOnMock -> {
            String jobId = (String) invocationOnMock.getArguments()[0];
            @SuppressWarnings("unchecked")
            Consumer<DataCounts> handler = (Consumer<DataCounts>) invocationOnMock.getArguments()[1];
            handler.accept(new DataCounts(jobId));
            return null;
        }).when(jobProvider).dataCounts(eq("my_id"), any(), any());

        AutodetectResultsParser parser = mock(AutodetectResultsParser.class);
        AutodetectProcess autodetectProcess = mock(AutodetectProcess.class);
        AutodetectProcessFactory autodetectProcessFactory = (j, modelSnapshot, quantiles, filters, i, e) -> autodetectProcess;
        AutodetectProcessManager manager = spy(new AutodetectProcessManager(Settings.EMPTY, client, threadPool, jobManager, jobProvider,
                jobResultsPersister, jobRenormalizedResultsPersister, jobDataCountsPersister, parser, autodetectProcessFactory,
                normalizerFactory));
        ModelSnapshot modelSnapshot = new ModelSnapshot("foo");
        Quantiles quantiles = new Quantiles("foo", new Date(), "state");
        Set<MlFilter> filters = new HashSet<>();
        doAnswer(invocationOnMock -> {
            AutodetectProcessManager.TriConsumer consumer = (AutodetectProcessManager.TriConsumer) invocationOnMock.getArguments()[1];
            consumer.accept(modelSnapshot, quantiles, filters);
            return null;
        }).when(manager).gatherRequiredInformation(any(), any(), any());

        expectThrows(EsRejectedExecutionException.class, () -> manager.create("my_id", modelSnapshot, quantiles, filters, false, e -> {}));
        verify(autodetectProcess, times(1)).close();
    }

    private void givenAllocationWithStatus(JobStatus status) {
        Allocation.Builder allocation = new Allocation.Builder();
        allocation.setStatus(status);
        when(jobManager.getJobAllocation("foo")).thenReturn(allocation.build());
    }

    private AutodetectProcessManager createManager(AutodetectCommunicator communicator) {
        Client client = mock(Client.class);
        return createManager(communicator, client);
    }

    private AutodetectProcessManager createManager(AutodetectCommunicator communicator, Client client) {
        ThreadPool threadPool = mock(ThreadPool.class);
        AutodetectResultsParser parser = mock(AutodetectResultsParser.class);
        AutodetectProcessFactory autodetectProcessFactory = mock(AutodetectProcessFactory.class);
        AutodetectProcessManager manager = new AutodetectProcessManager(Settings.EMPTY, client, threadPool, jobManager, jobProvider,
                jobResultsPersister, jobRenormalizedResultsPersister, jobDataCountsPersister, parser, autodetectProcessFactory,
                normalizerFactory);
        manager = spy(manager);
        ModelSnapshot modelSnapshot = new ModelSnapshot("foo");
        Quantiles quantiles = new Quantiles("foo", new Date(), "state");
        Set<MlFilter> filters = new HashSet<>();
        doAnswer(invocationOnMock -> {
            AutodetectProcessManager.TriConsumer consumer = (AutodetectProcessManager.TriConsumer) invocationOnMock.getArguments()[1];
            consumer.accept(modelSnapshot, quantiles, filters);
            return null;
        }).when(manager).gatherRequiredInformation(any(), any(), any());
        doReturn(communicator).when(manager).create(any(), eq(modelSnapshot), eq(quantiles), eq(filters), anyBoolean(), any());
        return manager;
    }

    private AutodetectProcessManager createManagerAndCallProcessData(AutodetectCommunicator communicator, String jobId) {
        AutodetectProcessManager manager = createManager(communicator);
        manager.openJob(jobId, false, e -> {});
        manager.processData(jobId, createInputStream(""), mock(DataLoadParams.class));
        return manager;
    }

    private Job createJobDetails(String jobId) {
        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setFormat(DataDescription.DataFormat.DELIMITED);
        dd.setFieldDelimiter(',');

        Detector d = new Detector.Builder("metric", "value").build();

        AnalysisConfig.Builder ac = new AnalysisConfig.Builder(Collections.singletonList(d));

        Job.Builder builder = new Job.Builder(jobId);
        builder.setDataDescription(dd);
        builder.setAnalysisConfig(ac);

        return builder.build();
    }

    private static InputStream createInputStream(String input) {
        return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    }
}
