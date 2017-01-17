/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.manager;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.ml.MlPlugin;
import org.elasticsearch.xpack.ml.action.UpdateJobStatusAction;
import org.elasticsearch.xpack.ml.job.DataCounts;
import org.elasticsearch.xpack.ml.job.Job;
import org.elasticsearch.xpack.ml.job.JobStatus;
import org.elasticsearch.xpack.ml.job.ModelSizeStats;
import org.elasticsearch.xpack.ml.job.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.data.DataProcessor;
import org.elasticsearch.xpack.ml.job.metadata.Allocation;
import org.elasticsearch.xpack.ml.job.persistence.JobDataCountsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobRenormalizedResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.UsagePersister;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectCommunicator;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcess;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessFactory;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.AutoDetectResultProcessor;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.AutodetectResultsParser;
import org.elasticsearch.xpack.ml.job.process.autodetect.output.StateProcessor;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.DataLoadParams;
import org.elasticsearch.xpack.ml.job.process.autodetect.params.InterimResultsParams;
import org.elasticsearch.xpack.ml.job.process.normalizer.NormalizerFactory;
import org.elasticsearch.xpack.ml.job.process.normalizer.Renormalizer;
import org.elasticsearch.xpack.ml.job.process.normalizer.ScoresUpdater;
import org.elasticsearch.xpack.ml.job.process.normalizer.ShortCircuitingRenormalizer;
import org.elasticsearch.xpack.ml.job.quantiles.Quantiles;
import org.elasticsearch.xpack.ml.job.status.StatusReporter;
import org.elasticsearch.xpack.ml.job.usage.UsageReporter;
import org.elasticsearch.xpack.ml.lists.ListDocument;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class AutodetectProcessManager extends AbstractComponent implements DataProcessor {

    // TODO (norelease) default needs to be reconsidered
    // Cannot be dynamic because the thread pool that is sized to match cannot be resized
    public static final Setting<Integer> MAX_RUNNING_JOBS_PER_NODE =
            Setting.intSetting("max_running_jobs", 10, 1, 512, Setting.Property.NodeScope);

    private final Client client;
    private final ThreadPool threadPool;
    private final JobManager jobManager;
    private final JobProvider jobProvider;
    private final AutodetectResultsParser parser;
    private final AutodetectProcessFactory autodetectProcessFactory;
    private final NormalizerFactory normalizerFactory;

    private final UsagePersister usagePersister;
    private final StateProcessor stateProcessor;
    private final JobResultsPersister jobResultsPersister;
    private final JobRenormalizedResultsPersister jobRenormalizedResultsPersister;
    private final JobDataCountsPersister jobDataCountsPersister;

    private final ConcurrentMap<String, AutodetectCommunicator> autoDetectCommunicatorByJob;

    private final int maxAllowedRunningJobs;

    public AutodetectProcessManager(Settings settings, Client client, ThreadPool threadPool, JobManager jobManager,
                                    JobProvider jobProvider, JobResultsPersister jobResultsPersister,
                                    JobRenormalizedResultsPersister jobRenormalizedResultsPersister,
                                    JobDataCountsPersister jobDataCountsPersister, AutodetectResultsParser parser,
                                    AutodetectProcessFactory autodetectProcessFactory, NormalizerFactory normalizerFactory) {
        super(settings);
        this.client = client;
        this.threadPool = threadPool;
        this.maxAllowedRunningJobs = MAX_RUNNING_JOBS_PER_NODE.get(settings);
        this.parser = parser;
        this.autodetectProcessFactory = autodetectProcessFactory;
        this.normalizerFactory = normalizerFactory;
        this.jobManager = jobManager;
        this.jobProvider = jobProvider;

        this.jobResultsPersister = jobResultsPersister;
        this.jobRenormalizedResultsPersister = jobRenormalizedResultsPersister;
        this.stateProcessor = new StateProcessor(settings, jobResultsPersister);
        this.usagePersister = new UsagePersister(settings, client);
        this.jobDataCountsPersister = jobDataCountsPersister;

        this.autoDetectCommunicatorByJob = new ConcurrentHashMap<>();
    }

    @Override
    public DataCounts processData(String jobId, InputStream input, DataLoadParams params) {
        Allocation allocation = jobManager.getJobAllocation(jobId);
        if (allocation.getStatus() != JobStatus.OPENED) {
            throw new IllegalArgumentException("job [" + jobId + "] status is [" + allocation.getStatus() + "], but must be ["
                    + JobStatus.OPENED + "] for processing data");
        }

        AutodetectCommunicator communicator = autoDetectCommunicatorByJob.get(jobId);
        if (communicator == null) {
            throw new IllegalStateException("job [" +  jobId + "] with status [" + allocation.getStatus() + "] hasn't been started");
        }
        try {
            return communicator.writeToJob(input, params);
            // TODO check for errors from autodetect
        } catch (IOException e) {
            String msg = String.format(Locale.ROOT, "Exception writing to process for job %s", jobId);
            if (e.getCause() instanceof TimeoutException) {
                logger.warn("Connection to process was dropped due to a timeout - if you are feeding this job from a connector it " +
                        "may be that your connector stalled for too long", e.getCause());
            } else {
                logger.error("Unexpected exception", e);
            }
            throw ExceptionsHelper.serverError(msg, e);
        }
    }

    @Override
    public void flushJob(String jobId, InterimResultsParams params) {
        logger.debug("Flushing job {}", jobId);
        AutodetectCommunicator communicator = autoDetectCommunicatorByJob.get(jobId);
        if (communicator == null) {
            String message = String.format(Locale.ROOT, "[%s] Cannot flush: no active autodetect process for job", jobId);
            logger.debug(message);
            throw new IllegalArgumentException(message);
        }
        try {
            communicator.flushJob(params);
            // TODO check for errors from autodetect
        } catch (IOException ioe) {
            String msg = String.format(Locale.ROOT, "[%s] exception while flushing job", jobId);
            logger.error(msg);
            throw ExceptionsHelper.serverError(msg, ioe);
        }
    }

    public void writeUpdateConfigMessage(String jobId, String config) throws IOException {
        AutodetectCommunicator communicator = autoDetectCommunicatorByJob.get(jobId);
        if (communicator == null) {
            logger.debug("Cannot update config: no active autodetect process for job {}", jobId);
            return;
        }
        communicator.writeUpdateConfigMessage(config);
        // TODO check for errors from autodetect
    }

    @Override
    public void openJob(String jobId, boolean ignoreDowntime, Consumer<Exception> handler) {
        gatherRequiredInformation(jobId, (modelSnapshot, quantiles, lists) -> {
            autoDetectCommunicatorByJob.computeIfAbsent(jobId, id -> {
                AutodetectCommunicator communicator = create(id, modelSnapshot, quantiles, lists, ignoreDowntime, handler);
                try {
                    communicator.writeJobInputHeader();
                } catch (IOException ioe) {
                    String msg = String.format(Locale.ROOT, "[%s] exception while opening job", jobId);
                    logger.error(msg);
                    throw ExceptionsHelper.serverError(msg, ioe);
                }
                setJobStatus(jobId, JobStatus.OPENED);
                return communicator;
            });
        }, handler);
    }

    void gatherRequiredInformation(String jobId, TriConsumer handler, Consumer<Exception> errorHandler) {
        Job job = jobManager.getJobOrThrowIfUnknown(jobId);
        jobProvider.modelSnapshots(jobId, 0, 1, page -> {
            ModelSnapshot modelSnapshot = page.results().isEmpty() ? null : page.results().get(1);
            jobProvider.getQuantiles(jobId, quantiles -> {
                String[] ids = job.getAnalysisConfig().extractReferencedLists().toArray(new String[0]);
                jobProvider.getLists(listDocument -> handler.accept(modelSnapshot, quantiles, listDocument), errorHandler, ids);
            }, errorHandler);
        }, errorHandler);
    }

    interface TriConsumer {

        void accept(ModelSnapshot modelSnapshot, Quantiles quantiles, Set<ListDocument> lists);

    }

    AutodetectCommunicator create(String jobId, ModelSnapshot modelSnapshot, Quantiles quantiles, Set<ListDocument> lists,
                                  boolean ignoreDowntime, Consumer<Exception> handler) {
        if (autoDetectCommunicatorByJob.size() == maxAllowedRunningJobs) {
            throw new ElasticsearchStatusException("max running job capacity [" + maxAllowedRunningJobs + "] reached",
                    RestStatus.CONFLICT);
        }

        // TODO norelease, once we remove black hole process
        // then we can  remove this method and move not enough threads logic to the auto detect process factory
        Job job = jobManager.getJobOrThrowIfUnknown(jobId);
        // A TP with no queue, so that we fail immediately if there are no threads available
        ExecutorService executorService = threadPool.executor(MlPlugin.AUTODETECT_PROCESS_THREAD_POOL_NAME);

        UsageReporter usageReporter = new UsageReporter(settings, job.getId(), usagePersister);
        try (StatusReporter statusReporter = new StatusReporter(threadPool, settings, job.getId(), fetchDataCounts(jobId),
                usageReporter, jobDataCountsPersister)) {
            ScoresUpdater scoresUpdator = new ScoresUpdater(job, jobProvider, jobRenormalizedResultsPersister, normalizerFactory);
            Renormalizer renormalizer = new ShortCircuitingRenormalizer(jobId, scoresUpdator,
                    threadPool.executor(MlPlugin.THREAD_POOL_NAME), job.getAnalysisConfig().getUsePerPartitionNormalization());
            AutoDetectResultProcessor processor = new AutoDetectResultProcessor(renormalizer, jobResultsPersister, parser);

            AutodetectProcess process = null;
            try {
                process = autodetectProcessFactory.createAutodetectProcess(job, modelSnapshot, quantiles, lists,
                        ignoreDowntime, executorService);
                return new AutodetectCommunicator(executorService, job, process, statusReporter, processor, stateProcessor, handler);
            } catch (Exception e) {
                try {
                    IOUtils.close(process);
                } catch (IOException ioe) {
                    logger.error("Can't close autodetect", ioe);
                }
                throw e;
            }
        }
    }

    private DataCounts fetchDataCounts(String jobId) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<DataCounts> holder = new AtomicReference<>();
        AtomicReference<Exception> errorHolder = new AtomicReference<>();
        jobProvider.dataCounts(jobId, dataCounts -> {
            holder.set(dataCounts);
            latch.countDown();
        }, e -> {
            errorHolder.set(e);
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (errorHolder.get() != null) {
            throw org.elasticsearch.ExceptionsHelper.convertToElastic(errorHolder.get());
        }
        return holder.get();
    }

    @Override
    public void closeJob(String jobId) {
        logger.debug("Closing job {}", jobId);
        AutodetectCommunicator communicator = autoDetectCommunicatorByJob.remove(jobId);
        if (communicator == null) {
            logger.debug("Cannot close: no active autodetect process for job {}", jobId);
            return;
        }

        try {
            communicator.close();
            setJobStatus(jobId, JobStatus.CLOSED);
        } catch (Exception e) {
            logger.warn("Exception closing stopped process input stream", e);
            throw ExceptionsHelper.serverError("Exception closing stopped process input stream", e);
        }
    }

    int numberOfOpenJobs() {
        return autoDetectCommunicatorByJob.size();
    }

    boolean jobHasActiveAutodetectProcess(String jobId) {
        return autoDetectCommunicatorByJob.get(jobId) != null;
    }

    public Duration jobUpTime(String jobId) {
        AutodetectCommunicator communicator = autoDetectCommunicatorByJob.get(jobId);
        if (communicator == null) {
            return Duration.ZERO;
        }
        return Duration.between(communicator.getProcessStartTime(), ZonedDateTime.now());
    }

    private void setJobStatus(String jobId, JobStatus status) {
        UpdateJobStatusAction.Request request = new UpdateJobStatusAction.Request(jobId, status);
        client.execute(UpdateJobStatusAction.INSTANCE, request, new ActionListener<UpdateJobStatusAction.Response>() {
            @Override
            public void onResponse(UpdateJobStatusAction.Response response) {
                if (response.isAcknowledged()) {
                    logger.info("Successfully set job status to [{}] for job [{}]", status, jobId);
                } else {
                    logger.info("Changing job status to [{}] for job [{}] wasn't acked", status, jobId);
                }
            }

            @Override
            public void onFailure(Exception e) {
                logger.error("Could not set job status to [" + status + "] for job [" + jobId +"]", e);
            }
        });
    }

    public void setJobStatus(String jobId, JobStatus status, Consumer<Void> handler, Consumer<Exception> errorHandler) {
        UpdateJobStatusAction.Request request = new UpdateJobStatusAction.Request(jobId, status);
        client.execute(UpdateJobStatusAction.INSTANCE, request, ActionListener.wrap(r -> handler.accept(null), errorHandler));
    }

    public Optional<ModelSizeStats> getModelSizeStats(String jobId) {
        AutodetectCommunicator communicator = autoDetectCommunicatorByJob.get(jobId);
        if (communicator == null) {
            return Optional.empty();
        }

        return communicator.getModelSizeStats();
    }

    public Optional<DataCounts> getDataCounts(String jobId) {
        AutodetectCommunicator communicator = autoDetectCommunicatorByJob.get(jobId);
        if (communicator == null) {
            return Optional.empty();
        }

        return communicator.getDataCounts();
    }
}
