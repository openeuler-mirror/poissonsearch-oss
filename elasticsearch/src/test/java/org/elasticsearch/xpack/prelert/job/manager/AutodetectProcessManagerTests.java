/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.manager;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.prelert.job.AnalysisConfig;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.DataDescription;
import org.elasticsearch.xpack.prelert.job.Detector;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.JobStatus;
import org.elasticsearch.xpack.prelert.job.metadata.Allocation;
import org.elasticsearch.xpack.prelert.job.persistence.JobProvider;
import org.elasticsearch.xpack.prelert.job.process.autodetect.AutodetectCommunicator;
import org.elasticsearch.xpack.prelert.job.process.autodetect.AutodetectProcessFactory;
import org.elasticsearch.xpack.prelert.job.process.autodetect.output.AutodetectResultsParser;
import org.elasticsearch.xpack.prelert.job.process.autodetect.params.DataLoadParams;
import org.elasticsearch.xpack.prelert.job.process.autodetect.params.InterimResultsParams;
import org.elasticsearch.xpack.prelert.job.process.autodetect.params.TimeRange;
import org.junit.Before;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.elasticsearch.mock.orig.Mockito.doThrow;
import static org.elasticsearch.mock.orig.Mockito.verify;
import static org.elasticsearch.mock.orig.Mockito.when;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.doReturn;
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

    @Before
    public void initMocks() {
        jobManager = Mockito.mock(JobManager.class);
        jobProvider = Mockito.mock(JobProvider.class);
        givenAllocationWithStatus(JobStatus.CLOSED);
    }

    public void testCreateProcessBySubmittingData()  {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);
        assertEquals(0, manager.numberOfRunningJobs());

        DataLoadParams params = new DataLoadParams(TimeRange.builder().build());
        manager.processData("foo", createInputStream(""), params);
        assertEquals(1, manager.numberOfRunningJobs());
    }

    public void testProcessDataThrowsElasticsearchStatusException_onIoException() throws Exception {
        AutodetectCommunicator communicator = Mockito.mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);

        InputStream inputStream = createInputStream("");
        doThrow(new IOException("blah")).when(communicator).writeToJob(inputStream);

        ESTestCase.expectThrows(ElasticsearchException.class,
                () -> manager.processData("foo", inputStream, mock(DataLoadParams.class)));
    }

    public void testCloseJob() {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(createJobDetails("foo"));
        AutodetectProcessManager manager = createManager(communicator);
        assertEquals(0, manager.numberOfRunningJobs());

        manager.processData("foo", createInputStream(""), mock(DataLoadParams.class));

        // job is created
        assertEquals(1, manager.numberOfRunningJobs());
        manager.closeJob("foo");
        assertEquals(0, manager.numberOfRunningJobs());
    }

    public void testBucketResetMessageIsSent() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);

        DataLoadParams params = new DataLoadParams(TimeRange.builder().startTime("1000").endTime("2000").build(), true);
        InputStream inputStream = createInputStream("");
        manager.processData("foo", inputStream, params);

        verify(communicator).writeResetBucketsControlMessage(params);
        verify(communicator).writeToJob(inputStream);
    }

    public void testFlush() throws IOException {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);
        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(createJobDetails("foo"));

        InputStream inputStream = createInputStream("");
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
        assertEquals("Exception flushing process for job foo", e.getMessage());
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

        manager.processData("foo", createInputStream(""), mock(DataLoadParams.class));

        assertTrue(manager.jobHasActiveAutodetectProcess("foo"));
        assertFalse(manager.jobHasActiveAutodetectProcess("bar"));
    }

    public void testProcessData_GivenPausingJob() {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        AutodetectProcessManager manager = createManager(communicator);

        Job job = createJobDetails("foo");

        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(job);
        givenAllocationWithStatus(JobStatus.PAUSING);

        InputStream inputStream = createInputStream("");
        DataCounts dataCounts = manager.processData("foo", inputStream, mock(DataLoadParams.class));

        assertThat(dataCounts, equalTo(new DataCounts("foo")));
    }

    public void testProcessData_GivenPausedJob() {
        AutodetectCommunicator communicator = mock(AutodetectCommunicator.class);
        Job job = createJobDetails("foo");
        when(jobManager.getJobOrThrowIfUnknown("foo")).thenReturn(job);
        givenAllocationWithStatus(JobStatus.PAUSED);
        AutodetectProcessManager manager = createManager(communicator);
        InputStream inputStream = createInputStream("");
        DataCounts dataCounts = manager.processData("foo", inputStream, mock(DataLoadParams.class));

        assertThat(dataCounts, equalTo(new DataCounts("foo")));
    }

    private void givenAllocationWithStatus(JobStatus status) {
        Allocation.Builder allocation = new Allocation.Builder();
        allocation.setStatus(status);
        when(jobManager.getJobAllocation("foo")).thenReturn(allocation.build());
    }

    private AutodetectProcessManager createManager(AutodetectCommunicator communicator) {
        Client client = mock(Client.class);
        Environment environment = mock(Environment.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        AutodetectResultsParser parser = mock(AutodetectResultsParser.class);
        AutodetectProcessFactory autodetectProcessFactory = mock(AutodetectProcessFactory.class);
        AutodetectProcessManager manager = new AutodetectProcessManager(Settings.EMPTY, client, environment, threadPool,
                                                    jobManager, jobProvider, parser, autodetectProcessFactory);
        manager = spy(manager);
        doReturn(communicator).when(manager).create(any(), anyBoolean());
        return manager;
    }

    private AutodetectProcessManager createManagerAndCallProcessData(AutodetectCommunicator communicator, String jobId) {
        AutodetectProcessManager manager = createManager(communicator);
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
