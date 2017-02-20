/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.ack.AckedRequest;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.ml.action.DeleteJobAction;
import org.elasticsearch.xpack.ml.action.PutJobAction;
import org.elasticsearch.xpack.ml.action.RevertModelSnapshotAction;
import org.elasticsearch.xpack.ml.action.util.QueryPage;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobState;
import org.elasticsearch.xpack.ml.job.config.JobUpdate;
import org.elasticsearch.xpack.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.job.metadata.MlMetadata;
import org.elasticsearch.xpack.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.ml.job.persistence.JobStorageDeletionTask;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.results.AnomalyRecord;
import org.elasticsearch.xpack.ml.notifications.Auditor;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.persistent.PersistentTasksInProgress;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Allows interactions with jobs. The managed interactions include:
 * <ul>
 * <li>creation</li>
 * <li>deletion</li>
 * <li>updating</li>
 * <li>starting/stopping of datafeed jobs</li>
 * </ul>
 */
public class JobManager extends AbstractComponent {

    /**
     * Field name in which to store the API version in the usage info
     */
    public static final String APP_VER_FIELDNAME = "appVer";

    public static final String DEFAULT_RECORD_SORT_FIELD = AnomalyRecord.PROBABILITY.getPreferredName();

    private final JobProvider jobProvider;
    private final ClusterService clusterService;
    private final JobResultsPersister jobResultsPersister;


    /**
     * Create a JobManager
     */
    public JobManager(Settings settings, JobProvider jobProvider, JobResultsPersister jobResultsPersister,
                      ClusterService clusterService) {
        super(settings);
        this.jobProvider = Objects.requireNonNull(jobProvider);
        this.clusterService = clusterService;
        this.jobResultsPersister = jobResultsPersister;
    }

    /**
     * Get the jobs that match the given {@code jobId}.
     * Note that when the {@code jocId} is {@link Job#ALL} all jobs are returned.
     *
     * @param jobId
     *            the jobId
     * @return A {@link QueryPage} containing the matching {@code Job}s
     */
    public QueryPage<Job> getJob(String jobId, ClusterState clusterState) {
        if (jobId.equals(Job.ALL)) {
            return getJobs(clusterState);
        }
        MlMetadata mlMetadata = clusterState.getMetaData().custom(MlMetadata.TYPE);
        Job job = mlMetadata.getJobs().get(jobId);
        if (job == null) {
            logger.debug(String.format(Locale.ROOT, "Cannot find job '%s'", jobId));
            throw ExceptionsHelper.missingJobException(jobId);
        }

        logger.debug("Returning job [" + jobId + "]");
        return new QueryPage<>(Collections.singletonList(job), 1, Job.RESULTS_FIELD);
    }

    /**
     * Get details of all Jobs.
     *
     * @return A query page object with hitCount set to the total number of jobs
     *         not the only the number returned here as determined by the
     *         <code>size</code> parameter.
     */
    public QueryPage<Job> getJobs(ClusterState clusterState) {
        MlMetadata mlMetadata = clusterState.getMetaData().custom(MlMetadata.TYPE);
        List<Job> jobs = mlMetadata.getJobs().entrySet().stream()
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        return new QueryPage<>(jobs, mlMetadata.getJobs().size(), Job.RESULTS_FIELD);
    }

    /**
     * Returns the non-null {@code Job} object for the given
     * {@code jobId} or throws
     * {@link org.elasticsearch.ResourceNotFoundException}
     *
     * @param jobId
     *            the jobId
     * @return the {@code Job} if a job with the given {@code jobId}
     *         exists
     * @throws org.elasticsearch.ResourceNotFoundException
     *             if there is no job with matching the given {@code jobId}
     */
    public Job getJobOrThrowIfUnknown(String jobId) {
        return getJobOrThrowIfUnknown(clusterService.state(), jobId);
    }

    public JobState getJobState(String jobId) {
        PersistentTasksInProgress tasks = clusterService.state().getMetaData().custom(PersistentTasksInProgress.TYPE);
        return MlMetadata.getJobState(jobId, tasks);
    }

    /**
     * Returns the non-null {@code Job} object for the given
     * {@code jobId} or throws
     * {@link org.elasticsearch.ResourceNotFoundException}
     *
     * @param jobId
     *            the jobId
     * @return the {@code Job} if a job with the given {@code jobId}
     *         exists
     * @throws org.elasticsearch.ResourceNotFoundException
     *             if there is no job with matching the given {@code jobId}
     */
    public static Job getJobOrThrowIfUnknown(ClusterState clusterState, String jobId) {
        MlMetadata mlMetadata = clusterState.metaData().custom(MlMetadata.TYPE);
        Job job = mlMetadata.getJobs().get(jobId);
        if (job == null) {
            throw ExceptionsHelper.missingJobException(jobId);
        }
        return job;
    }

    /**
     * Stores a job in the cluster state
     */
    public void putJob(PutJobAction.Request request, ClusterState state, ActionListener<PutJobAction.Response> actionListener) {
        Job job = request.getJob();

        ActionListener<Boolean> createResultsIndexListener = ActionListener.wrap(jobSaved ->
                jobProvider.createJobResultIndex(job, state, new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean indicesCreated) {
                audit(job.getId()).info(Messages.getMessage(Messages.JOB_AUDIT_CREATED));

                // Also I wonder if we need to audit log infra
                // structure in ml as when we merge into xpack
                // we can use its audit trailing. See:
                // https://github.com/elastic/prelert-legacy/issues/48
                actionListener.onResponse(new PutJobAction.Response(jobSaved && indicesCreated, job));
            }

            @Override
            public void onFailure(Exception e) {
                actionListener.onFailure(e);
            }
        }), actionListener::onFailure);

        clusterService.submitStateUpdateTask("put-job-" + job.getId(),
                new AckedClusterStateUpdateTask<Boolean>(request, createResultsIndexListener) {
                    @Override
                    protected Boolean newResponse(boolean acknowledged) {
                        return acknowledged;
                    }

                    @Override
                    public ClusterState execute(ClusterState currentState) throws Exception {
                        return updateClusterState(job, false, currentState);
                    }
                });
    }

    public void updateJob(String jobId, JobUpdate jobUpdate, AckedRequest request, Client client,
                          ActionListener<PutJobAction.Response> actionListener) {

        clusterService.submitStateUpdateTask("update-job-" + jobId,
                new AckedClusterStateUpdateTask<PutJobAction.Response>(request, actionListener) {
                    private Job updatedJob;

                    @Override
                    protected PutJobAction.Response newResponse(boolean acknowledged) {
                        return new PutJobAction.Response(acknowledged, updatedJob);
                    }

                    @Override
                    public ClusterState execute(ClusterState currentState) throws Exception {
                        Job job = getJob(jobId, currentState).results().get(0);
                        updatedJob = jobUpdate.mergeWithJob(job);
                        return updateClusterState(updatedJob, true, currentState);
                    }
                });
    }

    ClusterState updateClusterState(Job job, boolean overwrite, ClusterState currentState) {
        MlMetadata.Builder builder = createMlMetadataBuilder(currentState);
        builder.putJob(job, overwrite);
        return buildNewClusterState(currentState, builder);
    }


    public void deleteJob(DeleteJobAction.Request request, Client client, JobStorageDeletionTask task,
                          ActionListener<DeleteJobAction.Response> actionListener) {

        String jobId = request.getJobId();
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        logger.debug("Deleting job '" + jobId + "'");

        // Step 3. When the job has been removed from the cluster state, return a response
        // -------
        CheckedConsumer<Boolean, Exception> apiResponseHandler = jobDeleted -> {
            if (jobDeleted) {
                logger.info("Job [" + jobId + "] deleted.");
                actionListener.onResponse(new DeleteJobAction.Response(true));
                audit(jobId).info(Messages.getMessage(Messages.JOB_AUDIT_DELETED));
            } else {
                actionListener.onResponse(new DeleteJobAction.Response(false));
            }
        };

        // Step 2. When the physical storage has been deleted, remove from Cluster State
        // -------
        CheckedConsumer<Boolean, Exception> deleteJobStateHandler = response -> clusterService.submitStateUpdateTask("delete-job-" + jobId,
                new AckedClusterStateUpdateTask<Boolean>(request, ActionListener.wrap(apiResponseHandler, actionListener::onFailure)) {

                    @Override
                    protected Boolean newResponse(boolean acknowledged) {
                        return acknowledged && response;
                    }

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    MlMetadata.Builder builder = createMlMetadataBuilder(currentState);
                    builder.deleteJob(jobId, currentState.getMetaData().custom(PersistentTasksInProgress.TYPE));
                    return buildNewClusterState(currentState, builder);
                }
            });

        // Step 1. When the job has been marked as deleted then begin deleting the physical storage
        // -------
        CheckedConsumer<Boolean, Exception> updateHandler = response -> {
            // Successfully updated the status to DELETING, begin actually deleting
            if (response) {
                logger.info("Job [" + jobId + "] is successfully marked as deleted");
            } else {
                logger.warn("Job [" + jobId + "] marked as deleted wan't acknowledged");
            }

            // This task manages the physical deletion of the job (removing the results, then the index)
            task.delete(jobId, client,  clusterService.state(),
                    deleteJobStateHandler::accept, actionListener::onFailure);
        };

        // Step 0. Kick off the chain of callbacks with the initial UpdateStatus call
        // -------
        clusterService.submitStateUpdateTask("mark-job-as-deleted", new ClusterStateUpdateTask() {
            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                MlMetadata currentMlMetadata = currentState.metaData().custom(MlMetadata.TYPE);
                PersistentTasksInProgress tasks = currentState.metaData().custom(PersistentTasksInProgress.TYPE);
                MlMetadata.Builder builder = new MlMetadata.Builder(currentMlMetadata);
                builder.markJobAsDeleted(jobId, tasks);
                return buildNewClusterState(currentState, builder);
            }

            @Override
            public void onFailure(String source, Exception e) {
                actionListener.onFailure(e);
            }

            @Override
            public void clusterStatePublished(ClusterChangedEvent clusterChangedEvent) {
                try {
                    updateHandler.accept(true);
                } catch (Exception e) {
                    actionListener.onFailure(e);
                }
            }
        });
    }

    public Auditor audit(String jobId) {
        return jobProvider.audit(jobId);
    }

    public void revertSnapshot(RevertModelSnapshotAction.Request request, ActionListener<RevertModelSnapshotAction.Response> actionListener,
            ModelSnapshot modelSnapshot) {

        clusterService.submitStateUpdateTask("revert-snapshot-" + request.getJobId(),
                new AckedClusterStateUpdateTask<RevertModelSnapshotAction.Response>(request, actionListener) {

            @Override
            protected RevertModelSnapshotAction.Response newResponse(boolean acknowledged) {
                if (acknowledged) {
                    audit(request.getJobId())
                            .info(Messages.getMessage(Messages.JOB_AUDIT_REVERTED, modelSnapshot.getDescription()));
                    return new RevertModelSnapshotAction.Response(modelSnapshot);
                }
                throw new IllegalStateException("Could not revert modelSnapshot on job ["
                        + request.getJobId() + "], not acknowledged by master.");
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                Job job = getJobOrThrowIfUnknown(currentState, request.getJobId());
                Job.Builder builder = new Job.Builder(job);
                builder.setModelSnapshotId(modelSnapshot.getSnapshotId());
                return updateClusterState(builder.build(), true, currentState);
            }
        });
    }

    /**
     * Update a persisted model snapshot metadata document to match the
     * argument supplied.
     *
     * @param modelSnapshot         the updated model snapshot object to be stored
     */
    public void updateModelSnapshot(ModelSnapshot modelSnapshot, Consumer<Boolean> handler, Consumer<Exception> errorHandler) {
        jobResultsPersister.updateModelSnapshot(modelSnapshot, handler, errorHandler);
    }

    private static MlMetadata.Builder createMlMetadataBuilder(ClusterState currentState) {
        MlMetadata currentMlMetadata = currentState.metaData().custom(MlMetadata.TYPE);
        return new MlMetadata.Builder(currentMlMetadata);
    }

    private static ClusterState buildNewClusterState(ClusterState currentState, MlMetadata.Builder builder) {
        ClusterState.Builder newState = ClusterState.builder(currentState);
        newState.metaData(MetaData.builder(currentState.getMetaData()).putCustom(MlMetadata.TYPE, builder.build()).build());
        return newState.build();
    }

}
