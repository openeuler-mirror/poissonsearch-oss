/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.scheduler;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.FutureUtils;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.prelert.PrelertPlugin;
import org.elasticsearch.xpack.prelert.action.StartSchedulerAction;
import org.elasticsearch.xpack.prelert.action.UpdateSchedulerStatusAction;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.JobStatus;
import org.elasticsearch.xpack.prelert.job.audit.Auditor;
import org.elasticsearch.xpack.prelert.job.config.DefaultFrequency;
import org.elasticsearch.xpack.prelert.job.extraction.DataExtractor;
import org.elasticsearch.xpack.prelert.job.extraction.DataExtractorFactory;
import org.elasticsearch.xpack.prelert.job.metadata.Allocation;
import org.elasticsearch.xpack.prelert.job.metadata.PrelertMetadata;
import org.elasticsearch.xpack.prelert.job.persistence.BucketsQueryBuilder;
import org.elasticsearch.xpack.prelert.job.persistence.JobProvider;
import org.elasticsearch.xpack.prelert.job.results.Bucket;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ScheduledJobRunner extends AbstractComponent {

    private final Client client;
    private final ClusterService clusterService;
    private final JobProvider jobProvider;
    private final DataExtractorFactory dataExtractorFactory;
    private final ThreadPool threadPool;
    private final Supplier<Long> currentTimeSupplier;

    public ScheduledJobRunner(ThreadPool threadPool, Client client, ClusterService clusterService, JobProvider jobProvider,
                              DataExtractorFactory dataExtractorFactory, Supplier<Long> currentTimeSupplier) {
        super(Settings.EMPTY);
        this.threadPool = threadPool;
        this.clusterService = Objects.requireNonNull(clusterService);
        this.client = Objects.requireNonNull(client);
        this.jobProvider = Objects.requireNonNull(jobProvider);
        this.dataExtractorFactory = Objects.requireNonNull(dataExtractorFactory);
        this.currentTimeSupplier = Objects.requireNonNull(currentTimeSupplier);
    }

    public void run(String schedulerId, long startTime, Long endTime, StartSchedulerAction.SchedulerTask task,
                    Consumer<Exception> handler) {
        PrelertMetadata prelertMetadata = clusterService.state().metaData().custom(PrelertMetadata.TYPE);
        validate(schedulerId, prelertMetadata);

        setJobSchedulerStatus(schedulerId, SchedulerStatus.STARTED, error -> {
            if (error != null) {
                handler.accept(error);
                return;
            }

            Scheduler scheduler = prelertMetadata.getScheduler(schedulerId);
            Job job = prelertMetadata.getJobs().get(scheduler.getJobId());
            BucketsQueryBuilder.BucketsQuery latestBucketQuery = new BucketsQueryBuilder()
                    .sortField(Bucket.TIMESTAMP.getPreferredName())
                    .sortDescending(true).size(1)
                    .includeInterim(false)
                    .build();
            jobProvider.buckets(job.getId(), latestBucketQuery, buckets -> {
                long latestFinalBucketEndMs = -1L;
                Duration bucketSpan = Duration.ofSeconds(job.getAnalysisConfig().getBucketSpan());
                if (buckets.results().size() == 1) {
                    latestFinalBucketEndMs = buckets.results().get(0).getTimestamp().getTime() + bucketSpan.toMillis() - 1;
                }
                innerRun(scheduler, job, startTime, endTime, task, latestFinalBucketEndMs, handler);
            }, e -> {
                if (e instanceof ResourceNotFoundException) {
                    innerRun(scheduler, job, startTime, endTime, task, -1, handler);
                } else {
                    handler.accept(e);
                }
            });
        });
    }

    private void innerRun(Scheduler scheduler, Job job, long startTime, Long endTime, StartSchedulerAction.SchedulerTask task,
                          long latestFinalBucketEndMs, Consumer<Exception> handler) {
        logger.info("Starting scheduler [{}] for job [{}]", scheduler.getId(), scheduler.getJobId());
        Holder holder = createJobScheduler(scheduler, job, latestFinalBucketEndMs, handler);
        task.setHolder(holder);
        holder.future = threadPool.executor(PrelertPlugin.SCHEDULED_RUNNER_THREAD_POOL_NAME).submit(() -> {
            Long next = null;
            try {
                next = holder.scheduledJob.runLookBack(startTime, endTime);
            } catch (ScheduledJob.ExtractionProblemException e) {
                if (endTime == null) {
                    next = e.nextDelayInMsSinceEpoch;
                }
                holder.problemTracker.reportExtractionProblem(e.getCause().getMessage());
            } catch (ScheduledJob.AnalysisProblemException e) {
                if (endTime == null) {
                    next = e.nextDelayInMsSinceEpoch;
                }
                holder.problemTracker.reportAnalysisProblem(e.getCause().getMessage());
            } catch (ScheduledJob.EmptyDataCountException e) {
                if (endTime == null && holder.problemTracker.updateEmptyDataCount(true) == false) {
                    next = e.nextDelayInMsSinceEpoch;
                }
            } catch (Exception e) {
                logger.error("Failed lookback import for job [" + job.getId() + "]", e);
                holder.stop(e);
                return;
            }
            if (next != null) {
                doScheduleRealtime(next, job.getId(), holder);
            } else {
                holder.stop(null);
                holder.problemTracker.finishReport();
            }
        });
    }

    private void doScheduleRealtime(long delayInMsSinceEpoch, String jobId, Holder holder) {
        if (holder.isRunning()) {
            TimeValue delay = computeNextDelay(delayInMsSinceEpoch);
            logger.debug("Waiting [{}] before executing next realtime import for job [{}]", delay, jobId);
            holder.future = threadPool.schedule(delay, PrelertPlugin.SCHEDULED_RUNNER_THREAD_POOL_NAME, () -> {
                long nextDelayInMsSinceEpoch;
                try {
                    nextDelayInMsSinceEpoch = holder.scheduledJob.runRealtime();
                } catch (ScheduledJob.ExtractionProblemException e) {
                    nextDelayInMsSinceEpoch = e.nextDelayInMsSinceEpoch;
                    holder.problemTracker.reportExtractionProblem(e.getCause().getMessage());
                } catch (ScheduledJob.AnalysisProblemException e) {
                    nextDelayInMsSinceEpoch = e.nextDelayInMsSinceEpoch;
                    holder.problemTracker.reportAnalysisProblem(e.getCause().getMessage());
                } catch (ScheduledJob.EmptyDataCountException e) {
                    nextDelayInMsSinceEpoch = e.nextDelayInMsSinceEpoch;
                    if (holder.problemTracker.updateEmptyDataCount(true)) {
                        holder.problemTracker.finishReport();
                        holder.stop(e);
                        return;
                    }
                } catch (Exception e) {
                    logger.error("Unexpected scheduler failure for job [" + jobId + "] stopping...", e);
                    holder.stop(e);
                    return;
                }
                holder.problemTracker.finishReport();
                doScheduleRealtime(nextDelayInMsSinceEpoch, jobId, holder);
            });
        } else {
            holder.stop(null);
        }
    }

    public static void validate(String schedulerId, PrelertMetadata prelertMetadata) {
        Scheduler scheduler = prelertMetadata.getScheduler(schedulerId);
        if (scheduler == null) {
            throw ExceptionsHelper.missingSchedulerException(schedulerId);
        }
        Job job = prelertMetadata.getJobs().get(scheduler.getJobId());
        if (job == null) {
            throw ExceptionsHelper.missingJobException(scheduler.getJobId());
        }

        Allocation allocation = prelertMetadata.getAllocations().get(scheduler.getJobId());
        if (allocation.getStatus() != JobStatus.OPENED) {
            throw new ElasticsearchStatusException("cannot start scheduler, expected job status [{}], but got [{}]",
                    RestStatus.CONFLICT, JobStatus.OPENED, allocation.getStatus());
        }

        SchedulerStatus status = scheduler.getStatus();
        if (status != SchedulerStatus.STOPPED) {
            throw new ElasticsearchStatusException("scheduler already started, expected scheduler status [{}], but got [{}]",
                    RestStatus.CONFLICT, SchedulerStatus.STOPPED, status);
        }

        ScheduledJobValidator.validate(scheduler.getConfig(), job);
    }

    private Holder createJobScheduler(Scheduler scheduler, Job job, long latestFinalBucketEndMs, Consumer<Exception> handler) {
        Auditor auditor = jobProvider.audit(job.getId());
        Duration frequency = getFrequencyOrDefault(scheduler, job);
        Duration queryDelay = Duration.ofSeconds(scheduler.getConfig().getQueryDelay());
        DataExtractor dataExtractor = dataExtractorFactory.newExtractor(scheduler.getConfig(), job);
        ScheduledJob scheduledJob =  new ScheduledJob(job.getId(), frequency.toMillis(), queryDelay.toMillis(),
                dataExtractor, client, auditor, currentTimeSupplier, latestFinalBucketEndMs, getLatestRecordTimestamp(job.getId()));
        return new Holder(scheduler, scheduledJob, new ProblemTracker(() -> auditor), handler);
    }

    private long getLatestRecordTimestamp(String jobId) {
        long latestRecordTimeMs = -1L;
        DataCounts dataCounts = jobProvider.dataCounts(jobId);
        if (dataCounts.getLatestRecordTimeStamp() != null) {
            latestRecordTimeMs = dataCounts.getLatestRecordTimeStamp().getTime();
        }
        return latestRecordTimeMs;
    }

    private static Duration getFrequencyOrDefault(Scheduler scheduler, Job job) {
        Long frequency = scheduler.getConfig().getFrequency();
        Long bucketSpan = job.getAnalysisConfig().getBucketSpan();
        return frequency == null ? DefaultFrequency.ofBucketSpan(bucketSpan) : Duration.ofSeconds(frequency);
    }

    private TimeValue computeNextDelay(long next) {
        return new TimeValue(Math.max(1, next - currentTimeSupplier.get()));
    }

    private void setJobSchedulerStatus(String schedulerId, SchedulerStatus status, Consumer<Exception> handler) {
        UpdateSchedulerStatusAction.Request request = new UpdateSchedulerStatusAction.Request(schedulerId, status);
        client.execute(UpdateSchedulerStatusAction.INSTANCE, request, new ActionListener<UpdateSchedulerStatusAction.Response>() {
            @Override
            public void onResponse(UpdateSchedulerStatusAction.Response response) {
                if (response.isAcknowledged()) {
                    logger.debug("successfully set scheduler [{}] status to [{}]", schedulerId, status);
                } else {
                    logger.info("set scheduler [{}] status to [{}], but was not acknowledged", schedulerId, status);
                }
                handler.accept(null);
            }

            @Override
            public void onFailure(Exception e) {
                logger.error("could not set scheduler [" + schedulerId + "] status to [" + status + "]", e);
                handler.accept(e);
            }
        });
    }

    public class Holder {

        private final Scheduler scheduler;
        private final ScheduledJob scheduledJob;
        private final ProblemTracker problemTracker;
        private final Consumer<Exception> handler;
        volatile Future<?> future;

        private Holder(Scheduler scheduler, ScheduledJob scheduledJob, ProblemTracker problemTracker, Consumer<Exception> handler) {
            this.scheduler = scheduler;
            this.scheduledJob = scheduledJob;
            this.problemTracker = problemTracker;
            this.handler = handler;
        }

        boolean isRunning() {
            return scheduledJob.isRunning();
        }

        public void stop(Exception e) {
            logger.info("Stopping scheduler [{}] for job [{}]", scheduler.getId(), scheduler.getJobId());
            scheduledJob.stop();
            FutureUtils.cancel(future);
            setJobSchedulerStatus(scheduler.getId(), SchedulerStatus.STOPPED, error -> handler.accept(e));
        }

    }
}
