/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.writer;

import static org.elasticsearch.xpack.prelert.job.process.autodetect.writer.JsonDataToProcessWriterTests.endLessStream;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.tasks.TaskCancelledException;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.prelert.job.process.autodetect.AutodetectProcess;
import org.junit.Before;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import org.elasticsearch.xpack.prelert.job.AnalysisConfig;
import org.elasticsearch.xpack.prelert.job.DataDescription;
import org.elasticsearch.xpack.prelert.job.DataDescription.DataFormat;
import org.elasticsearch.xpack.prelert.job.Detector;
import org.elasticsearch.xpack.prelert.job.status.StatusReporter;
import org.elasticsearch.xpack.prelert.job.transform.TransformConfig;
import org.elasticsearch.xpack.prelert.job.transform.TransformConfigs;

public class SingleLineDataToProcessWriterTests extends ESTestCase {
    private AutodetectProcess autodetectProcess;
    private DataDescription.Builder dataDescription;
    private AnalysisConfig analysisConfig;
    private List<TransformConfig> transformConfigs;
    private StatusReporter statusReporter;

    private List<String[]> writtenRecords;

    @Before
    public void setUpMocks() throws IOException {
        autodetectProcess = Mockito.mock(AutodetectProcess.class);
        statusReporter = Mockito.mock(StatusReporter.class);

        writtenRecords = new ArrayList<>();
        doAnswer(new Answer<Void>() {
            @Override
            public Void answer(InvocationOnMock invocation) throws Throwable {
                String[] record = (String[]) invocation.getArguments()[0];
                String[] copy = Arrays.copyOf(record, record.length);
                writtenRecords.add(copy);
                return null;
            }
        }).when(autodetectProcess).writeRecord(any(String[].class));

        dataDescription = new DataDescription.Builder();
        dataDescription.setFieldDelimiter(',');
        dataDescription.setFormat(DataFormat.SINGLE_LINE);
        dataDescription.setTimeFormat("yyyy-MM-dd HH:mm:ssX");

        Detector.Builder detector = new Detector.Builder("count", null);
        detector.setByFieldName("message");
        analysisConfig = new AnalysisConfig.Builder(Arrays.asList(detector.build())).build();
        transformConfigs = new ArrayList<>();
    }

    public void testWrite_cancel() throws Exception {
        TransformConfig transformConfig = new TransformConfig("extract");
        transformConfig.setInputs(Arrays.asList("raw"));
        transformConfig.setOutputs(Arrays.asList("time", "message"));
        transformConfig.setArguments(Arrays.asList("(.{20}) (.*)"));
        transformConfigs.add(transformConfig);

        InputStream inputStream = endLessStream("", "2015-04-29 10:00:00Z this is a message\n");
        SingleLineDataToProcessWriter writer = createWriter();

        AtomicBoolean cancel = new AtomicBoolean(false);
        AtomicReference<Exception> exception = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                writer.write(inputStream, cancel::get);
            } catch (Exception e) {
                exception.set(e);
            }
        });
        t.start();
        try {
            assertBusy(() -> verify(statusReporter, atLeastOnce()).reportRecordWritten(anyLong(), anyLong()));
        } finally {
            cancel.set(true);
            t.join();
        }

        assertNotNull(exception.get());
        assertEquals(TaskCancelledException.class, exception.get().getClass());
        assertEquals("cancelled", exception.get().getMessage());
    }

    public void testWrite_GivenDataIsValid() throws Exception {
        TransformConfig transformConfig = new TransformConfig("extract");
        transformConfig.setInputs(Arrays.asList("raw"));
        transformConfig.setOutputs(Arrays.asList("time", "message"));
        transformConfig.setArguments(Arrays.asList("(.{20}) (.*)"));
        transformConfigs.add(transformConfig);

        StringBuilder input = new StringBuilder();
        input.append("2015-04-29 10:00:00Z This is message 1\n");
        input.append("2015-04-29 11:00:00Z This is message 2\r");
        input.append("2015-04-29 12:00:00Z This is message 3\r\n");
        InputStream inputStream = createInputStream(input.toString());
        SingleLineDataToProcessWriter writer = createWriter();

        writer.write(inputStream, () -> false);
        verify(statusReporter, times(1)).getLatestRecordTime();
        verify(statusReporter, times(1)).startNewIncrementalCount();
        verify(statusReporter, times(1)).setAnalysedFieldsPerRecord(1);
        verify(statusReporter, times(1)).reportRecordWritten(1, 1430301600000L);
        verify(statusReporter, times(1)).reportRecordWritten(1, 1430305200000L);
        verify(statusReporter, times(1)).reportRecordWritten(1, 1430308800000L);
        verify(statusReporter, times(1)).incrementalStats();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "message", "."});
        expectedRecords.add(new String[]{"1430301600", "This is message 1", ""});
        expectedRecords.add(new String[]{"1430305200", "This is message 2", ""});
        expectedRecords.add(new String[]{"1430308800", "This is message 3", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(statusReporter).finishReporting();
        verifyNoMoreInteractions(statusReporter);
    }

    public void testWrite_GivenDataContainsInvalidRecords() throws Exception {
        TransformConfig transformConfig = new TransformConfig("extract");
        transformConfig.setInputs(Arrays.asList("raw"));
        transformConfig.setOutputs(Arrays.asList("time", "message"));
        transformConfig.setArguments(Arrays.asList("(.{20}) (.*)"));
        transformConfigs.add(transformConfig);

        StringBuilder input = new StringBuilder();
        input.append("2015-04-29 10:00:00Z This is message 1\n");
        input.append("No transform\n");
        input.append("Transform can apply but no date to be parsed\n");
        input.append("\n");
        input.append("2015-04-29 12:00:00Z This is message 3\n");
        InputStream inputStream = createInputStream(input.toString());
        SingleLineDataToProcessWriter writer = createWriter();

        writer.write(inputStream, () -> false);
        verify(statusReporter, times(1)).getLatestRecordTime();
        verify(statusReporter, times(1)).startNewIncrementalCount();
        verify(statusReporter, times(1)).setAnalysedFieldsPerRecord(1);
        verify(statusReporter, times(1)).reportRecordWritten(1, 1430301600000L);
        verify(statusReporter, times(1)).reportRecordWritten(1, 1430308800000L);
        verify(statusReporter, times(3)).reportDateParseError(1);
        verify(statusReporter, times(1)).incrementalStats();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "message", "."});
        expectedRecords.add(new String[]{"1430301600", "This is message 1", ""});
        expectedRecords.add(new String[]{"1430308800", "This is message 3", ""});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(statusReporter).finishReporting();
        verifyNoMoreInteractions(statusReporter);
    }

    public void testWrite_GivenNoTransforms() throws Exception {
        StringBuilder input = new StringBuilder();
        input.append("2015-04-29 10:00:00Z This is message 1\n");
        InputStream inputStream = createInputStream(input.toString());
        SingleLineDataToProcessWriter writer = createWriter();

        writer.write(inputStream, () -> false);
        verify(statusReporter, times(1)).startNewIncrementalCount();
        verify(statusReporter, times(1)).setAnalysedFieldsPerRecord(1);
        verify(statusReporter, times(1)).reportDateParseError(1);
        verify(statusReporter, times(1)).incrementalStats();

        List<String[]> expectedRecords = new ArrayList<>();
        // The final field is the control field
        expectedRecords.add(new String[]{"time", "message", "."});
        assertWrittenRecordsEqualTo(expectedRecords);

        verify(statusReporter).getLatestRecordTime();
        verify(statusReporter).finishReporting();
        verifyNoMoreInteractions(statusReporter);
    }

    private static InputStream createInputStream(String input) {
        return new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
    }

    private SingleLineDataToProcessWriter createWriter() {
        return new SingleLineDataToProcessWriter(true, autodetectProcess, dataDescription.build(),
                analysisConfig, new TransformConfigs(transformConfigs), statusReporter, Mockito.mock(Logger.class));
    }

    private void assertWrittenRecordsEqualTo(List<String[]> expectedRecords) {
        assertEquals(expectedRecords.size(), writtenRecords.size());
        for (int i = 0; i < expectedRecords.size(); i++) {
            assertArrayEquals(expectedRecords.get(i), writtenRecords.get(i));
        }
    }
}
