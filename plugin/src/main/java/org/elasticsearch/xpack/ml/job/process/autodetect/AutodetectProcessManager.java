/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.process.autodetect;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.action.OpenJobAction.JobTask;
import org.elasticsearch.xpack.ml.calendars.SpecialEvent;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobState;
import org.elasticsearch.xpack.ml.job.config.JobTaskStatus;
import org.elasticsearch.xpack.ml.job.config.JobUpdate;
import org.elasticsearch.xpack.ml.job.config.ModelPlotConfig;
import org.elasticsearch.xpack.ml.job.persistence.JobDataCountsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobRenormalizedResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.StateStreamer;
import org.elasticsearch.xpack.ml.job.process.DataCountsReporter;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.AutoDetectResultProcessor;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.FlushAcknowledgement;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.AutodetectParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.DataLoadParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.FlushJobParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.ForecastParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.DataCounts;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSizeStats;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.process.normalizer.NormalizerFactory;
import org.elasticsearch.xpack.ml.job.process.normalizer.Renormalizer;
import org.elasticsearch.xpack.ml.job.process.normalizer.ScoresUpdater;
import org.elasticsearch.xpack.ml.job.process.normalizer.ShortCircuitingRenormalizer;
import org.elasticsearch.xpack.ml.notifications.Auditor;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.PersistentTask;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.elasticsearch.common.settings.Setting.Property;

public class AutodetectProcessManager extends AbstractComponent {

    // We should be able from the job config to estimate the memory/cpu a job needs to have,
    // and if we know that then we can prior to assigning a job to a node fail based on the
    // available resources on that node: https://github.com/elastic/x-pack-elasticsearch/issues/546
    // However, it is useful to also be able to apply a hard limit.

    // WARNING: These settings cannot be made DYNAMIC, because they are tied to several threadpools
    // and a threadpool's size can't be changed at runtime.
    // See MachineLearning#getExecutorBuilders(...)
    // TODO: Remove the deprecated setting in 7.0 and move the default value to the replacement setting
    @Deprecated
    public static final Setting<Integer> MAX_RUNNING_JOBS_PER_NODE =
            Setting.intSetting("max_running_jobs", 20, 1, 512, Property.NodeScope, Property.Deprecated);
    public static final Setting<Integer> MAX_OPEN_JOBS_PER_NODE =
            Setting.intSetting("xpack.ml.max_open_jobs", MAX_RUNNING_JOBS_PER_NODE, 1, Property.NodeScope);

    private final Client client;
    private final ThreadPool threadPool;
    private final JobManager jobManager;
    private final JobProvider jobProvider;
    private final AutodetectProcessFactory autodetectProcessFactory;
    private final NormalizerFactory normalizerFactory;

    private final JobResultsPersister jobResultsPersister;
    private final JobDataCountsPersister jobDataCountsPersister;

    private final ConcurrentMap<Long, ProcessContext> processByAllocation = new ConcurrentHashMap<>();

    private final int maxAllowedRunningJobs;

    private final NamedXContentRegistry xContentRegistry;

    private final Auditor auditor;

    public AutodetectProcessManager(Settings settings, Client client, ThreadPool threadPool,
                                    JobManager jobManager, JobProvider jobProvider, JobResultsPersister jobResultsPersister,
                                    JobDataCountsPersister jobDataCountsPersister,
                                    AutodetectProcessFactory autodetectProcessFactory, NormalizerFactory normalizerFactory,
                                    NamedXContentRegistry xContentRegistry, Auditor auditor) {
        super(settings);
        this.client = client;
        this.threadPool = threadPool;
        this.xContentRegistry = xContentRegistry;
        this.maxAllowedRunningJobs = MAX_OPEN_JOBS_PER_NODE.get(settings);
        this.autodetectProcessFactory = autodetectProcessFactory;
        this.normalizerFactory = normalizerFactory;
        this.jobManager = jobManager;
        this.jobProvider = jobProvider;
        this.jobResultsPersister = jobResultsPersister;
        this.jobDataCountsPersister = jobDataCountsPersister;
        this.auditor = auditor;
    }

    public synchronized void closeAllJobsOnThisNode(String reason) throws IOException {
        int numJobs = processByAllocation.size();
        if (numJobs != 0) {
            logger.info("Closing [{}] jobs, because [{}]", numJobs, reason);

            for (ProcessContext process : processByAllocation.values()) {
                closeJob(process.getJobTask(), false, reason);
            }
        }
    }

    public void killProcess(JobTask jobTask, boolean awaitCompletion, String reason) {
        ProcessContext processContext = processByAllocation.remove(jobTask.getAllocationId());
        if (processContext != null) {
            processContext.newKillBuilder()
                    .setAwaitCompletion(awaitCompletion)
                    .setFinish(true)
                    .setReason(reason)
                    .kill();
        }
    }

    public void killAllProcessesOnThisNode() {
        Iterator<ProcessContext> iterator = processByAllocation.values().iterator();
        while (iterator.hasNext()) {
            ProcessContext processContext = iterator.next();
            processContext.newKillBuilder()
                    .setAwaitCompletion(false)
                    .setFinish(false)
                    .setSilent(true)
                    .kill();
            iterator.remove();
        }
    }

    /**
     * Passes data to the native process.
     * This is a blocking call that won't return until all the data has been
     * written to the process.
     * <p>
     * An ElasticsearchStatusException will be thrown is any of these error conditions occur:
     * <ol>
     * <li>If a configured field is missing from the CSV header</li>
     * <li>If JSON data is malformed and we cannot recover parsing</li>
     * <li>If a high proportion of the records the timestamp field that cannot be parsed</li>
     * <li>If a high proportion of the records chronologically out of order</li>
     * </ol>
     *
     * @param jobTask       The job task
     * @param input         Data input stream
     * @param xContentType  the {@link XContentType} of the input
     * @param params        Data processing parameters
     * @param handler       Delegate error or datacount results (Count of records, fields, bytes, etc written)
     */
    public void processData(JobTask jobTask, InputStream input, XContentType xContentType,
                            DataLoadParams params, BiConsumer<DataCounts, Exception> handler) {
        AutodetectCommunicator communicator = getOpenAutodetectCommunicator(jobTask);
        if (communicator == null) {
            throw ExceptionsHelper.conflictStatusException("Cannot process data because job [" + jobTask.getJobId() + "] is not open");
        }
        communicator.writeToJob(input, xContentType, params, handler);
    }

    /**
     * Flush the running job, ensuring that the native process has had the
     * opportunity to process all data previously sent to it with none left
     * sitting in buffers.
     *
     * @param jobTask   The job task
     * @param params Parameters describing the controls that will accompany the flushing
     *               (e.g. calculating interim results, time control, etc.)
     */
    public void flushJob(JobTask jobTask, FlushJobParams params, ActionListener<FlushAcknowledgement> handler) {
        logger.debug("Flushing job {}", jobTask.getJobId());
        AutodetectCommunicator communicator = getOpenAutodetectCommunicator(jobTask);
        if (communicator == null) {
            String message = String.format(Locale.ROOT, "Cannot flush because job [%s] is not open", jobTask.getJobId());
            logger.debug(message);
            handler.onFailure(ExceptionsHelper.conflictStatusException(message));
            return;
        }

        communicator.flushJob(params, (flushAcknowledgement, e) -> {
            if (e != null) {
                String msg = String.format(Locale.ROOT, "[%s] exception while flushing job", jobTask.getJobId());
                logger.error(msg);
                handler.onFailure(ExceptionsHelper.serverError(msg, e));
            } else {
                handler.onResponse(flushAcknowledgement);
            }
        });
    }

    /**
     * Do a forecast for the running job.
     *
     * @param jobTask   The job task
     * @param params    Forecast parameters
     */
    public void forecastJob(JobTask jobTask, ForecastParams params, Consumer<Exception> handler) {
        logger.debug("Forecasting job {}", jobTask.getJobId());
        AutodetectCommunicator communicator = getOpenAutodetectCommunicator(jobTask);
        if (communicator == null) {
            String message = String.format(Locale.ROOT, "Cannot forecast because job [%s] is not open", jobTask.getJobId());
            logger.debug(message);
            handler.accept(ExceptionsHelper.conflictStatusException(message));
            return;
        }

        communicator.forecastJob(params, (aVoid, e) -> {
            if (e == null) {
                handler.accept(null);
            } else {
                String msg = String.format(Locale.ROOT, "[%s] exception while forecasting job", jobTask.getJobId());
                logger.error(msg, e);
                handler.accept(ExceptionsHelper.serverError(msg, e));
            }
        });
    }

    public void writeUpdateProcessMessage(JobTask jobTask, UpdateParams updateParams,
                                          Consumer<Exception> handler) {
        AutodetectCommunicator communicator = getOpenAutodetectCommunicator(jobTask);
        if (communicator == null) {
            String message = "Cannot process update model debug config because job [" + jobTask.getJobId() + "] is not open";
            logger.debug(message);
            handler.accept(ExceptionsHelper.conflictStatusException(message));
            return;
        }

        Consumer<List<SpecialEvent>> eventConsumer = specialEvents -> {
            communicator.writeUpdateProcessMessage(updateParams, specialEvents, (aVoid, e) -> {
                if (e == null) {
                    handler.accept(null);
                } else {
                    handler.accept(e);
                }
            });
        };


        if (updateParams.isUpdateSpecialEvents()) {
            jobProvider.specialEvents(jobTask.getJobId(), eventConsumer, handler::accept);
        } else {
            eventConsumer.accept(Collections.emptyList());
        }


    }

    public void openJob(JobTask jobTask, Consumer<Exception> handler) {
        String jobId = jobTask.getJobId();
        Job job = jobManager.getJobOrThrowIfUnknown(jobId);

        if (job.getJobVersion() == null) {
            handler.accept(ExceptionsHelper.badRequestException("Cannot open job [" + jobId
                    + "] because jobs created prior to version 5.5 are not supported"));
            return;
        }

        logger.info("Opening job [{}]", jobId);
        processByAllocation.putIfAbsent(jobTask.getAllocationId(), new ProcessContext(jobTask));
        jobProvider.getAutodetectParams(job, params -> {
            // We need to fork, otherwise we restore model state from a network thread (several GET api calls):
            threadPool.executor(MachineLearning.UTILITY_THREAD_POOL_NAME).execute(new AbstractRunnable() {
                @Override
                public void onFailure(Exception e) {
                    handler.accept(e);
                }

                @Override
                protected void doRun() throws Exception {
                    ProcessContext processContext = processByAllocation.get(jobTask.getAllocationId());
                    if (processContext == null) {
                        logger.debug("Aborted opening job [{}] as it has been closed", jobId);
                        return;
                    }
                    if (processContext.getState() !=  ProcessContext.ProcessStateName.NOT_RUNNING) {
                        logger.debug("Cannot open job [{}] when its state is [{}]", jobId, processContext.getState().getClass().getName());
                        return;
                    }

                    try {
                        createProcessAndSetRunning(processContext, params, handler);
                        processContext.getAutodetectCommunicator().init(params.modelSnapshot());
                        setJobState(jobTask, JobState.OPENED);
                    } catch (Exception e1) {
                        // No need to log here as the persistent task framework will log it
                        try {
                            // Don't leave a partially initialised process hanging around
                            processContext.newKillBuilder()
                                    .setAwaitCompletion(false)
                                    .setFinish(false)
                                    .kill();
                            processByAllocation.remove(jobTask.getAllocationId());
                        } finally {
                            setJobState(jobTask, JobState.FAILED, e2 -> handler.accept(e1));
                        }
                    }
                }
            });
        }, e1 -> {
            logger.warn("Failed to gather information required to open job [" + jobId + "]", e1);
            setJobState(jobTask, JobState.FAILED, e2 -> handler.accept(e1));
        });
    }

    private void createProcessAndSetRunning(ProcessContext processContext, AutodetectParams params, Consumer<Exception> handler) {
        // At this point we lock the process context until the process has been started.
        // The reason behind this is to ensure closing the job does not happen before
        // the process is started as that can result to the job getting seemingly closed
        // but the actual process is hanging alive.
        processContext.tryLock();
        try {
            AutodetectCommunicator communicator = create(processContext.getJobTask(), params, handler);
            processContext.setRunning(communicator);
        } finally {
            // Now that the process is running and we have updated its state we can unlock.
            // It is important to unlock before we initialize the communicator (ie. load the model state)
            // as that may be a long-running method.
            processContext.unlock();
        }
    }

    AutodetectCommunicator create(JobTask jobTask, AutodetectParams autodetectParams, Consumer<Exception> handler) {
        // Closing jobs can still be using some or all threads in MachineLearning.AUTODETECT_THREAD_POOL_NAME
        // that an open job uses, so include them too when considering if enough threads are available.
        int currentRunningJobs = processByAllocation.size();
        if (currentRunningJobs > maxAllowedRunningJobs) {
            throw new ElasticsearchStatusException("max running job capacity [" + maxAllowedRunningJobs + "] reached",
                    RestStatus.TOO_MANY_REQUESTS);
        }

        String jobId = jobTask.getJobId();
        notifyLoadingSnapshot(jobId, autodetectParams);

        if (autodetectParams.dataCounts().getProcessedRecordCount() > 0) {
            if (autodetectParams.modelSnapshot() == null) {
                String msg = "No model snapshot could be found for a job with processed records";
                logger.warn("[{}] {}", jobId, msg);
                auditor.warning(jobId, "No model snapshot could be found for a job with processed records");
            }
            if (autodetectParams.quantiles() == null) {
                String msg = "No quantiles could be found for a job with processed records";
                logger.warn("[{}] {}", jobId, msg);
                auditor.warning(jobId, msg);
            }
        }

        Job job = jobManager.getJobOrThrowIfUnknown(jobId);
        // A TP with no queue, so that we fail immediately if there are no threads available
        ExecutorService autoDetectExecutorService = threadPool.executor(MachineLearning.AUTODETECT_THREAD_POOL_NAME);
        DataCountsReporter dataCountsReporter = new DataCountsReporter(settings, job, autodetectParams.dataCounts(),
                jobDataCountsPersister);
        ScoresUpdater scoresUpdater = new ScoresUpdater(job, jobProvider,
                new JobRenormalizedResultsPersister(job.getId(), settings, client), normalizerFactory);
        ExecutorService renormalizerExecutorService = threadPool.executor(MachineLearning.UTILITY_THREAD_POOL_NAME);
        Renormalizer renormalizer = new ShortCircuitingRenormalizer(jobId, scoresUpdater,
                renormalizerExecutorService, job.getAnalysisConfig().getUsePerPartitionNormalization());

        AutodetectProcess process = autodetectProcessFactory.createAutodetectProcess(job, autodetectParams, autoDetectExecutorService,
                onProcessCrash(jobTask));
        AutoDetectResultProcessor processor = new AutoDetectResultProcessor(
                client, jobId, renormalizer, jobResultsPersister, jobProvider, autodetectParams.modelSizeStats(),
                autodetectParams.modelSnapshot() != null);
        ExecutorService autodetectWorkerExecutor;
        try (ThreadContext.StoredContext ignore = threadPool.getThreadContext().stashContext()) {
            autodetectWorkerExecutor = createAutodetectExecutorService(autoDetectExecutorService);
            autoDetectExecutorService.submit(() -> processor.process(process));
        } catch (EsRejectedExecutionException e) {
            // If submitting the operation to read the results from the process fails we need to close
            // the process too, so that other submitted operations to threadpool are stopped.
            try {
                IOUtils.close(process);
            } catch (IOException ioe) {
                logger.error("Can't close autodetect", ioe);
            }
            throw e;
        }
        return new AutodetectCommunicator(job, process, new StateStreamer(client), dataCountsReporter, processor, handler,
                xContentRegistry, autodetectWorkerExecutor);

    }

    private void notifyLoadingSnapshot(String jobId, AutodetectParams autodetectParams) {
        ModelSnapshot modelSnapshot = autodetectParams.modelSnapshot();
        StringBuilder msgBuilder = new StringBuilder("Loading model snapshot [");
        if (modelSnapshot == null) {
            msgBuilder.append("N/A");
        } else {
            msgBuilder.append(modelSnapshot.getSnapshotId());
            msgBuilder.append("] with latest_record_timestamp [");
            Date snapshotLatestRecordTimestamp = modelSnapshot.getLatestRecordTimeStamp();
            msgBuilder.append(snapshotLatestRecordTimestamp == null ? "N/A" :
                    XContentBuilder.DEFAULT_DATE_PRINTER.print(
                            snapshotLatestRecordTimestamp.getTime()));
        }
        msgBuilder.append("], job latest_record_timestamp [");
        Date jobLatestRecordTimestamp = autodetectParams.dataCounts().getLatestRecordTimeStamp();
        msgBuilder.append(jobLatestRecordTimestamp == null ? "N/A"
                : XContentBuilder.DEFAULT_DATE_PRINTER.print(jobLatestRecordTimestamp.getTime()));
        msgBuilder.append("]");
        String msg = msgBuilder.toString();
        logger.info("[{}] {}", jobId, msg);
        auditor.info(jobId, msg);
    }

    private Runnable onProcessCrash(JobTask jobTask) {
        return () -> {
            processByAllocation.remove(jobTask.getAllocationId());
            setJobState(jobTask, JobState.FAILED);
        };
    }

    /**
     * Stop the running job and mark it as finished.
     *
     * @param jobTask The job to stop
     * @param restart Whether the job should be restarted by persistent tasks
     * @param reason  The reason for closing the job
     */
    public void closeJob(JobTask jobTask, boolean restart, String reason) {
        String jobId = jobTask.getJobId();
        long allocationId = jobTask.getAllocationId();
        logger.debug("Attempting to close job [{}], because [{}]", jobId, reason);
        // don't remove the process context immediately, because we need to ensure
        // it is reachable to enable killing a job while it is closing
        ProcessContext processContext = processByAllocation.get(allocationId);
        if (processContext == null) {
            logger.debug("Cannot close job [{}] as it has already been closed", jobId);
            return;
        }

        processContext.tryLock();
        try {
            if (processContext.setDying() == false) {
                logger.debug("Cannot close job [{}] as it has already been closed", jobId);
                return;
            }

            if (reason == null) {
                logger.info("Closing job [{}]", jobId);
            } else {
                logger.info("Closing job [{}], because [{}]", jobId, reason);
            }

            AutodetectCommunicator communicator = processContext.getAutodetectCommunicator();
            if (communicator == null) {
                logger.debug("Job [{}] is being closed before its process is started", jobId);
                jobTask.markAsCompleted();
                return;
            }

            communicator.close(restart, reason);
            processByAllocation.remove(allocationId);
        } catch (Exception e) {
            // If the close failed because the process has explicitly been killed by us then just pass on that exception
            if (e instanceof ElasticsearchStatusException && ((ElasticsearchStatusException) e).status() == RestStatus.CONFLICT) {
                throw e;
            }
            logger.warn("[" + jobId + "] Exception closing autodetect process", e);
            setJobState(jobTask, JobState.FAILED);
            throw ExceptionsHelper.serverError("Exception closing autodetect process", e);
        } finally {
            // to ensure the contract that multiple simultaneous close calls for the same job wait until
            // the job is closed is honoured, hold the lock throughout the close procedure so that another
            // thread that gets into this method blocks until the first thread has finished closing the job
            processContext.unlock();
        }
    }

    int numberOfOpenJobs() {
        return (int) processByAllocation.values().stream()
                .filter(p -> p.getState() != ProcessContext.ProcessStateName.DYING)
                .count();
    }

    boolean jobHasActiveAutodetectProcess(JobTask jobTask) {
        return getAutodetectCommunicator(jobTask) != null;
    }

    private AutodetectCommunicator getAutodetectCommunicator(JobTask jobTask) {
        return processByAllocation.getOrDefault(jobTask.getAllocationId(), new ProcessContext(jobTask)).getAutodetectCommunicator();
    }

    private AutodetectCommunicator getOpenAutodetectCommunicator(JobTask jobTask) {
        ProcessContext processContext = processByAllocation.get(jobTask.getAllocationId());
        if (processContext != null && processContext.getState() == ProcessContext.ProcessStateName.RUNNING) {
            return processContext.getAutodetectCommunicator();
        }
        return null;
    }

    public Optional<Duration> jobOpenTime(JobTask jobTask) {
        AutodetectCommunicator communicator = getAutodetectCommunicator(jobTask);
        if (communicator == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(communicator.getProcessStartTime(), ZonedDateTime.now()));
    }

    void setJobState(JobTask jobTask, JobState state) {
        JobTaskStatus taskStatus = new JobTaskStatus(state, jobTask.getAllocationId());
        jobTask.updatePersistentStatus(taskStatus, new ActionListener<PersistentTask<?>>() {
            @Override
            public void onResponse(PersistentTask<?> persistentTask) {
                logger.info("Successfully set job state to [{}] for job [{}]", state, jobTask.getJobId());
            }

            @Override
            public void onFailure(Exception e) {
                logger.error("Could not set job state to [" + state + "] for job [" + jobTask.getJobId() + "]", e);
            }
        });
    }

    void setJobState(JobTask jobTask, JobState state, CheckedConsumer<Exception, IOException> handler) {
        JobTaskStatus taskStatus = new JobTaskStatus(state, jobTask.getAllocationId());
        jobTask.updatePersistentStatus(taskStatus, new ActionListener<PersistentTask<?>>() {
                    @Override
                    public void onResponse(PersistentTask<?> persistentTask) {
                        try {
                            handler.accept(null);
                        } catch (IOException e1) {
                            logger.warn("Error while delegating response", e1);
                        }
                    }

                    @Override
                    public void onFailure(Exception e) {
                        try {
                            handler.accept(e);
                        } catch (IOException e1) {
                            logger.warn("Error while delegating exception [" + e.getMessage() + "]", e1);
                        }
                    }
                });
    }

    public Optional<Tuple<DataCounts, ModelSizeStats>> getStatistics(JobTask jobTask) {
        AutodetectCommunicator communicator = getAutodetectCommunicator(jobTask);
        if (communicator == null) {
            return Optional.empty();
        }
        return Optional.of(new Tuple<>(communicator.getDataCounts(), communicator.getModelSizeStats()));
    }

    ExecutorService createAutodetectExecutorService(ExecutorService executorService) {
        AutodetectWorkerExecutorService autoDetectWorkerExecutor = new AutodetectWorkerExecutorService(threadPool.getThreadContext());
        executorService.submit(autoDetectWorkerExecutor::start);
        return autoDetectWorkerExecutor;
    }

    /*
     * The autodetect native process can only handle a single operation at a time. In order to guarantee that, all
     * operations are initially added to a queue and a worker thread from ml autodetect threadpool will process each
     * operation at a time.
     */
    class AutodetectWorkerExecutorService extends AbstractExecutorService {

        private final ThreadContext contextHolder;
        private final CountDownLatch awaitTermination = new CountDownLatch(1);
        private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(100);

        private volatile boolean running = true;

        AutodetectWorkerExecutorService(ThreadContext contextHolder) {
            this.contextHolder = contextHolder;
        }

        @Override
        public void shutdown() {
            running = false;
        }

        @Override
        public List<Runnable> shutdownNow() {
            throw new UnsupportedOperationException("not supported");
        }

        @Override
        public boolean isShutdown() {
            return running == false;
        }

        @Override
        public boolean isTerminated() {
            return awaitTermination.getCount() == 0;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return awaitTermination.await(timeout, unit);
        }

        @Override
        public void execute(Runnable command) {
            boolean added = queue.offer(contextHolder.preserveContext(command));
            if (added == false) {
                throw new ElasticsearchStatusException("Unable to submit operation", RestStatus.TOO_MANY_REQUESTS);
            }
        }

        void start() {
            try {
                while (running) {
                    Runnable runnable = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (runnable != null) {
                        try {
                            runnable.run();
                        } catch (Exception e) {
                            logger.error("error handling job operation", e);
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                awaitTermination.countDown();
            }
        }
    }
}
