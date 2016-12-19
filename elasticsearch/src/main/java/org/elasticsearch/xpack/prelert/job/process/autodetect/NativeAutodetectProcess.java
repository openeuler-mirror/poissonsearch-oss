/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Autodetect process using native code.
 */
class NativeAutodetectProcess implements AutodetectProcess {
    private static final Logger LOGGER = Loggers.getLogger(NativeAutodetectProcess.class);

    private final String jobId;
    private final CppLogMessageHandler cppLogHandler;
    private final OutputStream processInStream;
    private final InputStream processOutStream;
    private final InputStream persistStream;
    private final LengthEncodedWriter recordWriter;
    private final ZonedDateTime startTime;
    private final int numberOfAnalysisFields;
    private final List<Path> filesToDelete;
    private Future<?> logTailFuture;

    NativeAutodetectProcess(String jobId, InputStream logStream, OutputStream processInStream, InputStream processOutStream,
                            InputStream persistStream, int numberOfAnalysisFields, List<Path> filesToDelete,
                            ExecutorService executorService) throws EsRejectedExecutionException {
        this.jobId = jobId;
        cppLogHandler = new CppLogMessageHandler(jobId, logStream);
        this.processInStream = new BufferedOutputStream(processInStream);
        this.processOutStream = processOutStream;
        this.persistStream = persistStream;
        this.recordWriter = new LengthEncodedWriter(this.processInStream);
        startTime = ZonedDateTime.now();
        this.numberOfAnalysisFields = numberOfAnalysisFields;
        this.filesToDelete = filesToDelete;
        logTailFuture = executorService.submit(() -> {
            try (CppLogMessageHandler h = cppLogHandler) {
                h.tailStream();
            } catch (IOException e) {
                LOGGER.error(new ParameterizedMessage("[{}] Error tailing C++ process logs", new Object[] { jobId }), e);
            }
        });
    }

    @Override
    public void writeRecord(String[] record) throws IOException {
        recordWriter.writeRecord(record);
    }

    @Override
    public void writeResetBucketsControlMessage(DataLoadParams params) throws IOException {
        ControlMsgToProcessWriter writer = new ControlMsgToProcessWriter(recordWriter, numberOfAnalysisFields);
        writer.writeResetBucketsMessage(params);
    }

    @Override
    public void writeUpdateConfigMessage(String config) throws IOException {
        ControlMsgToProcessWriter writer = new ControlMsgToProcessWriter(recordWriter, numberOfAnalysisFields);
        writer.writeUpdateConfigMessage(config);
    }

    @Override
    public String flushJob(InterimResultsParams params) throws IOException {
        ControlMsgToProcessWriter writer = new ControlMsgToProcessWriter(recordWriter, numberOfAnalysisFields);
        writer.writeCalcInterimMessage(params);
        return writer.writeFlushMessage();
    }

    @Override
    public void flushStream() throws IOException {
        recordWriter.flush();
    }

    @Override
    public void close() throws IOException {
        try {
            // closing its input causes the process to exit
            processInStream.close();
            // wait for the process to exit by waiting for end-of-file on the named pipe connected to its logger
            // this may take a long time as it persists the model state
            logTailFuture.get(30, TimeUnit.MINUTES);
            if (cppLogHandler.seenFatalError()) {
                throw ExceptionsHelper.serverError(cppLogHandler.getErrors());
            }
            LOGGER.debug("[{}] Autodetect process exited", jobId);
        } catch (ExecutionException | TimeoutException e) {
            LOGGER.warn(new ParameterizedMessage("[{}] Exception closing the running autodetect process",
                    new Object[] { jobId }), e);
        } catch (InterruptedException e) {
            LOGGER.warn("[{}] Exception closing the running autodetect process", jobId);
            Thread.currentThread().interrupt();
        } finally {
            deleteAssociatedFiles();
        }
    }

    void deleteAssociatedFiles() throws IOException {
        if (filesToDelete == null) {
            return;
        }

        for (Path fileToDelete : filesToDelete) {
            if (Files.deleteIfExists(fileToDelete)) {
                LOGGER.debug("[{}] Deleted file {}", jobId, fileToDelete.toString());
            } else {
                LOGGER.warn("[{}] Failed to delete file {}", jobId, fileToDelete.toString());
            }
        }
    }

    @Override
    public InputStream getProcessOutStream() {
        return processOutStream;
    }

    @Override
    public InputStream getPersistStream() {
        return persistStream;
    }

    @Override
    public ZonedDateTime getProcessStartTime() {
        return startTime;
    }

    @Override
    public boolean isProcessAlive() {
        // Sanity check: make sure the process hasn't terminated already
        return !cppLogHandler.hasLogStreamEnded();
    }

    @Override
    public String readError() {
        return cppLogHandler.getErrors();
    }
}
