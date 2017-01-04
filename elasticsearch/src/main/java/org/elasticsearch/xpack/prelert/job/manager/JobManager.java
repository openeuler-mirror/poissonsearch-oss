/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.manager;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.prelert.action.DeleteJobAction;
import org.elasticsearch.xpack.prelert.action.OpenJobAction;
import org.elasticsearch.xpack.prelert.action.PutJobAction;
import org.elasticsearch.xpack.prelert.action.RevertModelSnapshotAction;
import org.elasticsearch.xpack.prelert.action.UpdateJobStatusAction;
import org.elasticsearch.xpack.prelert.action.UpdateSchedulerStatusAction;
import org.elasticsearch.xpack.prelert.job.IgnoreDowntime;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.JobStatus;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.prelert.job.audit.Auditor;
import org.elasticsearch.xpack.prelert.job.messages.Messages;
import org.elasticsearch.xpack.prelert.job.metadata.Allocation;
import org.elasticsearch.xpack.prelert.job.metadata.PrelertMetadata;
import org.elasticsearch.xpack.prelert.job.persistence.JobProvider;
import org.elasticsearch.xpack.prelert.job.persistence.JobResultsPersister;
import org.elasticsearch.xpack.prelert.job.persistence.QueryPage;
import org.elasticsearch.xpack.prelert.job.results.AnomalyRecord;
import org.elasticsearch.xpack.prelert.scheduler.SchedulerStatus;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Allows interactions with jobs. The managed interactions include:
 * <ul>
 * <li>creation</li>
 * <li>deletion</li>
 * <li>updating</li>
 * <li>starting/stopping of scheduled jobs</li>
 * </ul>
 */
public class JobManager extends AbstractComponent {

    private static final Logger LOGGER = Loggers.getLogger(JobManager.class);

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
        PrelertMetadata prelertMetadata = clusterState.getMetaData().custom(PrelertMetadata.TYPE);
        Job job = prelertMetadata.getJobs().get(jobId);
        if (job == null) {
            logger.debug(String.format(Locale.ROOT, "Cannot find job '%s'", jobId));
            throw QueryPage.emptyQueryPage(Job.RESULTS_FIELD);
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
        PrelertMetadata prelertMetadata = clusterState.getMetaData().custom(PrelertMetadata.TYPE);
        List<Job> jobs = prelertMetadata.getJobs().entrySet().stream()
                .map(Map.Entry::getValue)
                .collect(Collectors.toList());
        return new QueryPage<>(jobs, prelertMetadata.getJobs().size(), Job.RESULTS_FIELD);
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

    public Allocation getJobAllocation(String jobId) {
        return getAllocation(clusterService.state(), jobId);
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
    Job getJobOrThrowIfUnknown(ClusterState clusterState, String jobId) {
        PrelertMetadata prelertMetadata = clusterState.metaData().custom(PrelertMetadata.TYPE);
        Job job = prelertMetadata.getJobs().get(jobId);
        if (job == null) {
            throw ExceptionsHelper.missingJobException(jobId);
        }
        return job;
    }

    /**
     * Stores a job in the cluster state
     */
    public void putJob(PutJobAction.Request request, ActionListener<PutJobAction.Response> actionListener) {
        Job job = request.getJob();

        ActionListener<Boolean> delegateListener = ActionListener.wrap(jobSaved ->
                jobProvider.createJobResultIndex(job, new ActionListener<Boolean>() {
            @Override
            public void onResponse(Boolean indicesCreated) {
                audit(job.getId()).info(Messages.getMessage(Messages.JOB_AUDIT_CREATED));

                // Also I wonder if we need to audit log infra
                // structure in prelert as when we merge into xpack
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
                new AckedClusterStateUpdateTask<Boolean>(request, delegateListener) {

            @Override
            protected Boolean newResponse(boolean acknowledged) {
                return acknowledged;
            }

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                ClusterState cs = updateClusterState(job, request.isOverwrite(), currentState);
                if (currentState.metaData().index(AnomalyDetectorsIndex.jobResultsIndexName(job.getIndexName())) != null) {
                    throw new ResourceAlreadyExistsException(Messages.getMessage(Messages.JOB_INDEX_ALREADY_EXISTS,
                            AnomalyDetectorsIndex.jobResultsIndexName(job.getIndexName())));
                }
                return cs;
            }
        });
    }

    ClusterState updateClusterState(Job job, boolean overwrite, ClusterState currentState) {
        PrelertMetadata.Builder builder = createPrelertMetadataBuilder(currentState);
        builder.putJob(job, overwrite);
        return buildNewClusterState(currentState, builder);
    }


    public void deleteJob(Client client, DeleteJobAction.Request request, ActionListener<DeleteJobAction.Response> actionListener) {

        String jobId = request.getJobId();
        String indexName = AnomalyDetectorsIndex.jobResultsIndexName(jobId);
        LOGGER.debug("Deleting job '" + jobId + "'");


        // Step 3. Listen for the Cluster State status change
        //         Chain acknowledged status onto original actionListener
        CheckedConsumer<Boolean, Exception> deleteStatusConsumer =  jobDeleted -> {
            if (jobDeleted) {
                logger.info("Job [" + jobId + "] deleted.");
                actionListener.onResponse(new DeleteJobAction.Response(true));

                //nocommit: needs #626, because otherwise the audit message re-creates the index
                // we just deleted.  :)
                //audit(jobId).info(Messages.getMessage(Messages.JOB_AUDIT_DELETED));
            } else {
                actionListener.onResponse(new DeleteJobAction.Response(false));
            }
        };


        // Step 2. Listen for the Deleted Index response
        //         If successful, delete from cluster state and chain onto deleteStatusListener
        CheckedConsumer<DeleteIndexResponse, Exception> deleteIndexConsumer = response -> {
            logger.info("Deleting index [" + indexName + "] successful");

            if (response.isAcknowledged()) {
                logger.info("Index deletion acknowledged");
            } else {
                logger.warn("Index deletion not acknowledged");
            }
            clusterService.submitStateUpdateTask("delete-job-" + jobId,
                new AckedClusterStateUpdateTask<Boolean>(request, ActionListener.wrap(deleteStatusConsumer, actionListener::onFailure)) {

                @Override
                protected Boolean newResponse(boolean acknowledged) {
                    return acknowledged && response.isAcknowledged();
                }

                @Override
                public ClusterState execute(ClusterState currentState) throws Exception {
                    return removeJobFromState(jobId, currentState);
                }
            });

        };

        // Step 1. Update the CS to DELETING
        //         If successful, attempt to delete the physical index and chain
        //         onto deleteIndexConsumer
        CheckedConsumer<UpdateJobStatusAction.Response, Exception> updateConsumer = response -> {
            // Sucessfully updated the status to DELETING, begin actually deleting
            if (response.isAcknowledged()) {
                logger.info("Job [" + jobId + "] set to [" + JobStatus.DELETING + "]");
            } else {
                logger.warn("Job [" + jobId + "] change to [" + JobStatus.DELETING + "] was not acknowledged.");
            }

            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName);
            client.admin().indices().delete(deleteIndexRequest, ActionListener.wrap(deleteIndexConsumer, actionListener::onFailure));
        };

        UpdateJobStatusAction.Request updateStatusListener = new UpdateJobStatusAction.Request(jobId, JobStatus.DELETING);
        setJobStatus(updateStatusListener, ActionListener.wrap(updateConsumer, actionListener::onFailure));

    }

    ClusterState removeJobFromState(String jobId, ClusterState currentState) {
        PrelertMetadata.Builder builder = createPrelertMetadataBuilder(currentState);
        builder.deleteJob(jobId);
        return buildNewClusterState(currentState, builder);
    }

    public void updateSchedulerStatus(UpdateSchedulerStatusAction.Request request,
                                      ActionListener<UpdateSchedulerStatusAction.Response> actionListener) {
        String schedulerId = request.getSchedulerId();
        SchedulerStatus newStatus = request.getSchedulerStatus();
        clusterService.submitStateUpdateTask("update-scheduler-status-" + schedulerId,
                new AckedClusterStateUpdateTask<UpdateSchedulerStatusAction.Response>(request, actionListener) {

            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                PrelertMetadata.Builder builder = createPrelertMetadataBuilder(currentState);
                builder.updateSchedulerStatus(schedulerId, newStatus);
                return buildNewClusterState(currentState, builder);
            }

            @Override
            protected UpdateSchedulerStatusAction.Response newResponse(boolean acknowledged) {
                return new UpdateSchedulerStatusAction.Response(acknowledged);
            }
        });
    }

    private Allocation getAllocation(ClusterState state, String jobId) {
        PrelertMetadata prelertMetadata = state.metaData().custom(PrelertMetadata.TYPE);
        Allocation allocation = prelertMetadata.getAllocations().get(jobId);
        if (allocation == null) {
            throw new ResourceNotFoundException("No allocation found for job with id [" + jobId + "]");
        }
        return allocation;
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
                if (request.getDeleteInterveningResults()) {
                    builder.setIgnoreDowntime(IgnoreDowntime.NEVER);
                } else {
                    builder.setIgnoreDowntime(IgnoreDowntime.ONCE);
                }

                return updateClusterState(builder.build(), true, currentState);
            }
        });
    }

    public void openJob(OpenJobAction.Request request, ActionListener<OpenJobAction.Response> actionListener) {
        clusterService.submitStateUpdateTask("open-job-" + request.getJobId(),
                new AckedClusterStateUpdateTask<OpenJobAction.Response>(request, actionListener) {
            @Override
            public ClusterState execute(ClusterState currentState) throws Exception {
                PrelertMetadata.Builder builder = new PrelertMetadata.Builder(currentState.metaData().custom(PrelertMetadata.TYPE));
                builder.updateStatus(request.getJobId(), JobStatus.OPENING, null);
                if (request.isIgnoreDowntime()) {
                    builder.setIgnoreDowntime(request.getJobId());
                }
                return ClusterState.builder(currentState)
                        .metaData(MetaData.builder(currentState.metaData()).putCustom(PrelertMetadata.TYPE, builder.build()))
                        .build();
            }

            @Override
            protected OpenJobAction.Response newResponse(boolean acknowledged) {
                return new OpenJobAction.Response(acknowledged);
            }
        });
    }

    public void setJobStatus(UpdateJobStatusAction.Request request, ActionListener<UpdateJobStatusAction.Response> actionListener) {
        clusterService.submitStateUpdateTask("set-job-status-" + request.getStatus() + "-" + request.getJobId(),
                new AckedClusterStateUpdateTask<UpdateJobStatusAction.Response>(request, actionListener) {

                    @Override
                    public ClusterState execute(ClusterState currentState) throws Exception {
                        PrelertMetadata.Builder builder = new PrelertMetadata.Builder(currentState.metaData().custom(PrelertMetadata.TYPE));
                        builder.updateStatus(request.getJobId(), request.getStatus(), request.getReason());
                        return ClusterState.builder(currentState)
                                .metaData(MetaData.builder(currentState.metaData()).putCustom(PrelertMetadata.TYPE, builder.build()))
                                .build();
                    }

                    @Override
                    protected UpdateJobStatusAction.Response newResponse(boolean acknowledged) {
                        return new UpdateJobStatusAction.Response(acknowledged);
                    }
                });
    }

    /**
     * Update a persisted model snapshot metadata document to match the
     * argument supplied.
     *
     * @param jobId                 the job id
     * @param modelSnapshot         the updated model snapshot object to be stored
     * @param restoreModelSizeStats should the model size stats in this
     *                              snapshot be made the current ones for this job?
     */
    public void updateModelSnapshot(String jobId, ModelSnapshot modelSnapshot, boolean restoreModelSizeStats) {
        // For Elasticsearch the update can be done in exactly the same way as
        // the original persist
        jobResultsPersister.persistModelSnapshot(modelSnapshot);
        if (restoreModelSizeStats) {
            if (modelSnapshot.getModelSizeStats() != null) {
                jobResultsPersister.persistModelSizeStats(modelSnapshot.getModelSizeStats());
            }
            if (modelSnapshot.getQuantiles() != null) {
                jobResultsPersister.persistQuantiles(modelSnapshot.getQuantiles());
            }
        }
        // Commit so that when the REST API call that triggered the update
        // returns the updated document is searchable
        jobResultsPersister.commitStateWrites(jobId);
    }

    private static PrelertMetadata.Builder createPrelertMetadataBuilder(ClusterState currentState) {
        PrelertMetadata currentPrelertMetadata = currentState.metaData().custom(PrelertMetadata.TYPE);
        return new PrelertMetadata.Builder(currentPrelertMetadata);
    }

    private static ClusterState buildNewClusterState(ClusterState currentState, PrelertMetadata.Builder builder) {
        ClusterState.Builder newState = ClusterState.builder(currentState);
        newState.metaData(MetaData.builder(currentState.getMetaData()).putCustom(PrelertMetadata.TYPE, builder.build()).build());
        return newState.build();
    }

}
