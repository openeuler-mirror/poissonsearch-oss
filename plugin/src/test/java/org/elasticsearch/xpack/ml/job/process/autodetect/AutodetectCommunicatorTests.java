/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ml.calendars.ScheduledEvent;
import org.elasticsearch.xpack.ml.calendars.ScheduledEventTests;
import org.elasticsearch.xpack.ml.job.config.AnalysisConfig;
import org.elasticsearch.xpack.ml.job.config.DataDescription;
import org.elasticsearch.xpack.ml.job.config.DetectionRule;
import org.elasticsearch.xpack.ml.job.config.Detector;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobUpdate;
import org.elasticsearch.xpack.ml.job.config.RuleCondition;
import org.elasticsearch.xpack.ml.job.persistence.StateStreamer;
import org.elasticsearch.xpack.ml.job.process.DataCountsReporter;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.AutoDetectResultProcessor;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.FlushAcknowledgement;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.DataLoadParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.FlushJobParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.TimeRange;
import org.junit.Before;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.elasticsearch.mock.orig.Mockito.doAnswer;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class AutodetectCommunicatorTests extends ESTestCase {

    private StateStreamer stateStreamer;

    @Before
    public void initMocks() {
        stateStreamer = mock(StateStreamer.class);
    }

    public void testWriteResetBucketsControlMessage() throws IOException {
        DataLoadParams params = new DataLoadParams(TimeRange.builder().startTime("1").endTime("2").build(), Optional.empty());
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        try (AutodetectCommunicator communicator = createAutodetectCommunicator(process, mock(AutoDetectResultProcessor.class))) {
            communicator.writeToJob(new ByteArrayInputStream(new byte[0]),
                    randomFrom(XContentType.values()), params, (dataCounts, e) -> {});
            verify(process).writeResetBucketsControlMessage(params);
        }
    }

    public void testWriteUpdateProcessMessage() throws IOException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        when(process.isReady()).thenReturn(true);
        AutodetectCommunicator communicator = createAutodetectCommunicator(process, mock(AutoDetectResultProcessor.class));

        List<RuleCondition> conditions = Collections.singletonList(
                RuleCondition.createCategorical("foo", "bar"));

        List<JobUpdate.DetectorUpdate> detectorUpdates = Collections.singletonList(
                new JobUpdate.DetectorUpdate(0, "updated description",
                        Collections.singletonList(new DetectionRule.Builder(conditions).build())));

        UpdateParams updateParams = new UpdateParams(null, detectorUpdates, true);
        List<ScheduledEvent> events = Collections.singletonList(ScheduledEventTests.createScheduledEvent(randomAlphaOfLength(10)));

        communicator.writeUpdateProcessMessage(updateParams, events, ((aVoid, e) -> {}));

        // There are 2 detectors both will be updated with the rule for the scheduled event.
        // The first has an additional update rule
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        InOrder inOrder = Mockito.inOrder(process);
        inOrder.verify(process).writeUpdateDetectorRulesMessage(eq(0), captor.capture());
        assertEquals(2, captor.getValue().size());
        inOrder.verify(process).writeUpdateDetectorRulesMessage(eq(1), captor.capture());
        assertEquals(1, captor.getValue().size());
        verify(process).isProcessAlive();
        verifyNoMoreInteractions(process);


        // This time there is a single detector update and no scheduled events
        detectorUpdates = Collections.singletonList(
                new JobUpdate.DetectorUpdate(1, "updated description",
                        Collections.singletonList(new DetectionRule.Builder(conditions).build())));
        updateParams = new UpdateParams(null, detectorUpdates, true);
        communicator.writeUpdateProcessMessage(updateParams, Collections.emptyList(), ((aVoid, e) -> {}));

        inOrder = Mockito.inOrder(process);
        inOrder.verify(process).writeUpdateDetectorRulesMessage(eq(1), captor.capture());
        assertEquals(1, captor.getValue().size());
        verify(process, times(2)).isProcessAlive();
    }

    public void testFlushJob() throws IOException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        when(process.isProcessAlive()).thenReturn(true);
        AutoDetectResultProcessor processor = mock(AutoDetectResultProcessor.class);
        FlushAcknowledgement flushAcknowledgement = mock(FlushAcknowledgement.class);
        when(processor.waitForFlushAcknowledgement(anyString(), any())).thenReturn(flushAcknowledgement);
        try (AutodetectCommunicator communicator = createAutodetectCommunicator(process, processor)) {
            FlushJobParams params = FlushJobParams.builder().build();
            AtomicReference<FlushAcknowledgement> flushAcknowledgementHolder = new AtomicReference<>();
            communicator.flushJob(params, (f, e) -> flushAcknowledgementHolder.set(f));
            assertThat(flushAcknowledgementHolder.get(), equalTo(flushAcknowledgement));
            Mockito.verify(process).flushJob(params);
        }
    }

    public void testWaitForFlushReturnsIfParserFails() throws IOException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        when(process.isProcessAlive()).thenReturn(true);
        AutoDetectResultProcessor processor = mock(AutoDetectResultProcessor.class);
        when(processor.isFailed()).thenReturn(true);
        when(processor.waitForFlushAcknowledgement(anyString(), any())).thenReturn(null);
        AutodetectCommunicator communicator = createAutodetectCommunicator(process, processor);
        expectThrows(ElasticsearchException.class, () -> communicator.waitFlushToCompletion("foo"));
    }

    public void testFlushJob_throwsIfProcessIsDead() throws IOException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        when(process.isProcessAlive()).thenReturn(false);
        when(process.readError()).thenReturn("Mock process is dead");
        AutodetectCommunicator communicator = createAutodetectCommunicator(process, mock(AutoDetectResultProcessor.class));
        FlushJobParams params = FlushJobParams.builder().build();
        Exception[] holder = new ElasticsearchException[1];
        communicator.flushJob(params, (aVoid, e1) -> holder[0] = e1);
        assertEquals("[foo] Unexpected death of autodetect: Mock process is dead", holder[0].getMessage());
    }

    public void testFlushJob_givenFlushWaitReturnsTrueOnSecondCall() throws IOException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        when(process.isProcessAlive()).thenReturn(true);
        AutoDetectResultProcessor autoDetectResultProcessor = Mockito.mock(AutoDetectResultProcessor.class);
        FlushAcknowledgement flushAcknowledgement = mock(FlushAcknowledgement.class);
        when(autoDetectResultProcessor.waitForFlushAcknowledgement(anyString(), eq(Duration.ofSeconds(1))))
                .thenReturn(null).thenReturn(flushAcknowledgement);
        FlushJobParams params = FlushJobParams.builder().build();

        try (AutodetectCommunicator communicator = createAutodetectCommunicator(process, autoDetectResultProcessor)) {
            communicator.flushJob(params, (aVoid, e) -> {});
        }

        verify(autoDetectResultProcessor, times(2)).waitForFlushAcknowledgement(anyString(), eq(Duration.ofSeconds(1)));
        // First in checkAndRun, second due to check between calls to waitForFlushAcknowledgement and third due to close()
        verify(process, times(3)).isProcessAlive();
    }

    public void testCloseGivenProcessIsReady() throws IOException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        when(process.isReady()).thenReturn(true);
        AutodetectCommunicator communicator = createAutodetectCommunicator(process, mock(AutoDetectResultProcessor.class));

        communicator.close();

        verify(process).close();
        verify(process, never()).kill();
        Mockito.verifyNoMoreInteractions(stateStreamer);
    }

    public void testCloseGivenProcessIsNotReady() throws IOException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        when(process.isReady()).thenReturn(false);
        AutodetectCommunicator communicator = createAutodetectCommunicator(process, mock(AutoDetectResultProcessor.class));

        communicator.close();

        verify(process).kill();
        verify(process, never()).close();
        verify(stateStreamer).cancel();
    }

    public void testKill() throws IOException, TimeoutException {
        AutodetectProcess process = mockAutodetectProcessWithOutputStream();
        AutoDetectResultProcessor resultProcessor = mock(AutoDetectResultProcessor.class);
        ExecutorService executorService = mock(ExecutorService.class);

        AtomicBoolean finishCalled = new AtomicBoolean(false);
        AutodetectCommunicator communicator = createAutodetectCommunicator(executorService, process, resultProcessor,
                e -> finishCalled.set(true));
        boolean awaitCompletion = randomBoolean();
        boolean finish = randomBoolean();
        communicator.killProcess(awaitCompletion, finish);
        Mockito.verify(resultProcessor).setProcessKilled();
        Mockito.verify(process).kill();
        Mockito.verify(executorService).shutdown();
        if (awaitCompletion) {
            Mockito.verify(resultProcessor).awaitCompletion();
        } else {
            Mockito.verify(resultProcessor, never()).awaitCompletion();
        }
        assertEquals(finish, finishCalled.get());
    }

    private Job createJobDetails() {
        Job.Builder builder = new Job.Builder("foo");

        DataDescription.Builder dd = new DataDescription.Builder();
        dd.setTimeField("time_field");

        Detector.Builder metric = new Detector.Builder("metric", "value");
        metric.setByFieldName("host-metric");
        Detector.Builder count = new Detector.Builder("count", null);
        AnalysisConfig.Builder ac = new AnalysisConfig.Builder(Arrays.asList(metric.build(), count.build()));

        builder.setDataDescription(dd);
        builder.setAnalysisConfig(ac);
        return builder.build(new Date());
    }

    private AutodetectProcess mockAutodetectProcessWithOutputStream() throws IOException {
        AutodetectProcess process = Mockito.mock(AutodetectProcess.class);
        when(process.isProcessAlive()).thenReturn(true);
        return process;
    }

    private AutodetectCommunicator createAutodetectCommunicator(ExecutorService executorService, AutodetectProcess autodetectProcess,
                                                                AutoDetectResultProcessor autoDetectResultProcessor,
                                                                Consumer<Exception> finishHandler) throws IOException {
        DataCountsReporter dataCountsReporter = mock(DataCountsReporter.class);
        doAnswer(invocation -> {
            ((ActionListener<Boolean>) invocation.getArguments()[0]).onResponse(true);
            return null;
        }).when(dataCountsReporter).finishReporting(any());
        return new AutodetectCommunicator(createJobDetails(), autodetectProcess, stateStreamer,
                dataCountsReporter, autoDetectResultProcessor, finishHandler,
                new NamedXContentRegistry(Collections.emptyList()), executorService);
    }

    private AutodetectCommunicator createAutodetectCommunicator(AutodetectProcess autodetectProcess,
                                                                AutoDetectResultProcessor autoDetectResultProcessor) throws IOException {
        ExecutorService executorService = mock(ExecutorService.class);
        when(executorService.submit(any(Callable.class))).thenReturn(mock(Future.class));
        doAnswer(invocationOnMock -> {
            Callable runnable = (Callable) invocationOnMock.getArguments()[0];
            runnable.call();
            return mock(Future.class);
        }).when(executorService).submit(any(Callable.class));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArguments()[0]).run();
            return null;
        }).when(executorService).execute(any(Runnable.class));

        return createAutodetectCommunicator(executorService, autodetectProcess, autoDetectResultProcessor, e -> {});
    }

}
