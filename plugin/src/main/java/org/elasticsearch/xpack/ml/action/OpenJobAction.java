/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.MasterNodeRequest;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.license.LicenseUtils;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobTaskStatus;
import org.elasticsearch.xpack.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessManager;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.persistent.AllocatedPersistentTask;
import org.elasticsearch.xpack.persistent.PersistentTaskParams;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.Assignment;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.PersistentTask;
import org.elasticsearch.xpack.persistent.PersistentTasksExecutor;
import org.elasticsearch.xpack.persistent.PersistentTasksService;
import org.elasticsearch.xpack.persistent.PersistentTasksService.WaitForPersistentTaskStatusListener;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

import static org.elasticsearch.xpack.ml.job.process.autodetect.AutodetectProcessManager.MAX_RUNNING_JOBS_PER_NODE;

public class OpenJobAction extends Action<OpenJobAction.Request, OpenJobAction.Response, OpenJobAction.RequestBuilder> {

    public static final OpenJobAction INSTANCE = new OpenJobAction();
    public static final String NAME = "cluster:admin/xpack/ml/job/open";
    public static final String TASK_NAME = "xpack/ml/job";

    private OpenJobAction() {
        super(NAME);
    }

    @Override
    public RequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new RequestBuilder(client, this);
    }

    @Override
    public Response newResponse() {
        return new Response();
    }

    public static class Request extends MasterNodeRequest<Request> implements ToXContent {

        public static Request fromXContent(XContentParser parser) {
            return parseRequest(null, parser);
        }

        public static Request parseRequest(String jobId, XContentParser parser) {
            JobParams jobParams = JobParams.PARSER.apply(parser, null);
            if (jobId != null) {
                jobParams.jobId = jobId;
            }
            return new Request(jobParams);
        }

        private JobParams jobParams;

        public Request(JobParams jobParams) {
            this.jobParams = jobParams;
        }

        public Request(String jobId) {
            this.jobParams = new JobParams(jobId);
        }

        public Request(StreamInput in) throws IOException {
            readFrom(in);
        }

        Request() {
        }

        public JobParams getJobParams() {
            return jobParams;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobParams = new JobParams(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            jobParams.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            jobParams.toXContent(builder, params);
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobParams);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || obj.getClass() != getClass()) {
                return false;
            }
            OpenJobAction.Request other = (OpenJobAction.Request) obj;
            return Objects.equals(jobParams, other.jobParams);
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }
    }

    public static class JobParams implements PersistentTaskParams {

        public static final ParseField IGNORE_DOWNTIME = new ParseField("ignore_downtime");
        public static final ParseField TIMEOUT = new ParseField("timeout");
        public static ObjectParser<JobParams, Void> PARSER = new ObjectParser<>(TASK_NAME, JobParams::new);

        static {
            PARSER.declareString(JobParams::setJobId, Job.ID);
            PARSER.declareBoolean(JobParams::setIgnoreDowntime, IGNORE_DOWNTIME);
            PARSER.declareString((params, val) ->
                    params.setTimeout(TimeValue.parseTimeValue(val, TIMEOUT.getPreferredName())), TIMEOUT);
        }

        public static JobParams fromXContent(XContentParser parser) {
            return parseRequest(null, parser);
        }

        public static JobParams parseRequest(String jobId, XContentParser parser) {
            JobParams params = PARSER.apply(parser, null);
            if (jobId != null) {
                params.jobId = jobId;
            }
            return params;
        }

        private String jobId;
        private boolean ignoreDowntime = true;
        // A big state can take a while to restore.  For symmetry with the _close endpoint any
        // changes here should be reflected there too.
        private TimeValue timeout = MachineLearning.STATE_PERSIST_RESTORE_TIMEOUT;

        JobParams() {
        }

        public JobParams(String jobId) {
            this.jobId = ExceptionsHelper.requireNonNull(jobId, Job.ID.getPreferredName());
        }

        public JobParams(StreamInput in) throws IOException {
            jobId = in.readString();
            ignoreDowntime = in.readBoolean();
            timeout = TimeValue.timeValueMillis(in.readVLong());
        }

        public String getJobId() {
            return jobId;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public boolean isIgnoreDowntime() {
            return ignoreDowntime;
        }

        public void setIgnoreDowntime(boolean ignoreDowntime) {
            this.ignoreDowntime = ignoreDowntime;
        }

        public TimeValue getTimeout() {
            return timeout;
        }

        public void setTimeout(TimeValue timeout) {
            this.timeout = timeout;
        }

        @Override
        public String getWriteableName() {
            return TASK_NAME;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(jobId);
            out.writeBoolean(ignoreDowntime);
            out.writeVLong(timeout.millis());
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Job.ID.getPreferredName(), jobId);
            builder.field(IGNORE_DOWNTIME.getPreferredName(), ignoreDowntime);
            builder.field(TIMEOUT.getPreferredName(), timeout.getStringRep());
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, ignoreDowntime, timeout);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || obj.getClass() != getClass()) {
                return false;
            }
            OpenJobAction.JobParams other = (OpenJobAction.JobParams) obj;
            return Objects.equals(jobId, other.jobId) &&
                    Objects.equals(ignoreDowntime, other.ignoreDowntime) &&
                    Objects.equals(timeout, other.timeout);
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }
    }

    public static class Response extends AcknowledgedResponse {
        public Response() {
            super();
        }

        public Response(boolean acknowledged) {
            super(acknowledged);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            readAcknowledged(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            writeAcknowledged(out);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            AcknowledgedResponse that = (AcknowledgedResponse) o;
            return isAcknowledged() == that.isAcknowledged();
        }

        @Override
        public int hashCode() {
            return Objects.hash(isAcknowledged());
        }

    }

    public static class JobTask extends AllocatedPersistentTask {

        private final String jobId;
        private volatile AutodetectProcessManager autodetectProcessManager;

        JobTask(String jobId, long id, String type, String action, TaskId parentTask) {
            super(id, type, action, "job-" + jobId, parentTask);
            this.jobId = jobId;
        }

        public String getJobId() {
            return jobId;
        }

        @Override
        protected void onCancelled() {
            String reason = getReasonCancelled();
            closeJob(reason);
        }

        void closeJob(String reason) {
            autodetectProcessManager.closeJob(this, false, reason);
        }

        static boolean match(Task task, String expectedJobId) {
            String expectedDescription = "job-" + expectedJobId;
            return task instanceof JobTask && expectedDescription.equals(task.getDescription());
        }

    }

    static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        RequestBuilder(ElasticsearchClient client, OpenJobAction action) {
            super(client, action, new Request());
        }
    }

    // This class extends from TransportMasterNodeAction for cluster state observing purposes.
    // The close job api also redirect the elected master node.
    // The master node will wait for the job to be opened by checking the persistent task's status and then return.
    // To ensure that a subsequent close job call will see that same task status (and sanity validation doesn't fail)
    // both open and close job apis redirect to the elected master node.
    // In case of instability persistent tasks checks may fail and that is ok, in that case all bets are off.
    // The open job api is a low through put api, so the fact that we redirect to elected master node shouldn't be an issue.
    public static class TransportAction extends TransportMasterNodeAction<Request, Response> {

        private final XPackLicenseState licenseState;
        private final PersistentTasksService persistentTasksService;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ThreadPool threadPool, XPackLicenseState licenseState,
                               ClusterService clusterService, PersistentTasksService persistentTasksService, ActionFilters actionFilters,
                               IndexNameExpressionResolver indexNameExpressionResolver) {
            super(settings, NAME, transportService, clusterService, threadPool, actionFilters, indexNameExpressionResolver, Request::new);
            this.licenseState = licenseState;
            this.persistentTasksService = persistentTasksService;
        }

        @Override
        protected String executor() {
            // This api doesn't do heavy or blocking operations (just delegates PersistentTasksService),
            // so we can do this on the network thread
            return ThreadPool.Names.SAME;
        }

        @Override
        protected Response newResponse() {
            return new Response();
        }

        @Override
        protected void masterOperation(Request request, ClusterState state, ActionListener<Response> listener) throws Exception {
            JobParams jobParams = request.getJobParams();
            if (licenseState.isMachineLearningAllowed()) {
                ActionListener<PersistentTask<JobParams>> finalListener = new ActionListener<PersistentTask<JobParams>>() {
                    @Override
                    public void onResponse(PersistentTask<JobParams> task) {
                        waitForJobStarted(task.getId(), jobParams, listener);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (e instanceof ResourceAlreadyExistsException) {
                            e = new ElasticsearchStatusException("Cannot open job [" + jobParams.getJobId() +
                                    "] because it has already been opened", RestStatus.CONFLICT, e);
                        }
                        listener.onFailure(e);
                    }
                };
                persistentTasksService.startPersistentTask(MlMetadata.jobTaskId(jobParams.jobId), TASK_NAME, jobParams, finalListener);
            } else {
                listener.onFailure(LicenseUtils.newComplianceException(XPackPlugin.MACHINE_LEARNING));
            }
        }

        @Override
        protected ClusterBlockException checkBlock(Request request, ClusterState state) {
            // We only delegate here to PersistentTasksService, but if there is a metadata writeblock,
            // then delagating to PersistentTasksService doesn't make a whole lot of sense,
            // because PersistentTasksService will then fail.
            return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
        }

        void waitForJobStarted(String taskId, JobParams jobParams, ActionListener<Response> listener) {
            JobPredicate predicate = new JobPredicate();
            persistentTasksService.waitForPersistentTaskStatus(taskId, predicate, jobParams.timeout,
                    new WaitForPersistentTaskStatusListener<JobParams>() {
                @Override
                public void onResponse(PersistentTask<JobParams> persistentTask) {
                    listener.onResponse(new Response(predicate.opened));
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }

                @Override
                public void onTimeout(TimeValue timeout) {
                    listener.onFailure(new ElasticsearchException("Opening job ["
                            + jobParams.getJobId() + "] timed out after [" + timeout + "]"));
                }
            });
        }

        private class JobPredicate implements Predicate<PersistentTask<?>> {

            private volatile boolean opened;

            @Override
            public boolean test(PersistentTask<?> persistentTask) {
                if (persistentTask == null) {
                    return false;
                }
                JobTaskStatus jobState = (JobTaskStatus) persistentTask.getStatus();
                if (jobState == null) {
                    return false;
                }
                switch (jobState.getState()) {
                    case OPENED:
                        opened = true;
                        return true;
                    case FAILED:
                        return true;
                    default:
                        throw new IllegalStateException("Unexpected job state [" + jobState + "]");

                }
            }
        }
    }

    public static class OpenJobPersistentTasksExecutor extends PersistentTasksExecutor<JobParams> {

        private final AutodetectProcessManager autodetectProcessManager;

        private final int maxNumberOfOpenJobs;
        private volatile int maxConcurrentJobAllocations;

        public OpenJobPersistentTasksExecutor(Settings settings, ClusterService clusterService,
                                              AutodetectProcessManager autodetectProcessManager) {
            super(settings, TASK_NAME, MachineLearning.UTILITY_THREAD_POOL_NAME);
            this.autodetectProcessManager = autodetectProcessManager;
            this.maxNumberOfOpenJobs = AutodetectProcessManager.MAX_RUNNING_JOBS_PER_NODE.get(settings);
            this.maxConcurrentJobAllocations = MachineLearning.CONCURRENT_JOB_ALLOCATIONS.get(settings);
            clusterService.getClusterSettings()
                    .addSettingsUpdateConsumer(MachineLearning.CONCURRENT_JOB_ALLOCATIONS, this::setMaxConcurrentJobAllocations);
        }

        @Override
        public Assignment getAssignment(JobParams params, ClusterState clusterState) {
            return selectLeastLoadedMlNode(params.getJobId(), clusterState, maxConcurrentJobAllocations, maxNumberOfOpenJobs, logger);
        }

        @Override
        public void validate(JobParams params, ClusterState clusterState) {
            // If we already know that we can't find an ml node because all ml nodes are running at capacity or
            // simply because there are no ml nodes in the cluster then we fail quickly here:
            MlMetadata mlMetadata = clusterState.metaData().custom(MlMetadata.TYPE);
            OpenJobAction.validate(params.getJobId(), mlMetadata);
            Assignment assignment = selectLeastLoadedMlNode(params.getJobId(), clusterState, maxConcurrentJobAllocations,
                    maxNumberOfOpenJobs, logger);
            if (assignment.getExecutorNode() == null) {
                String msg = "Could not open job because no suitable nodes were found, allocation explanation ["
                        + assignment.getExplanation() + "]";
                logger.warn("[{}] {}", params.getJobId(), msg);
                throw new ElasticsearchStatusException(msg, RestStatus.TOO_MANY_REQUESTS);
            }
        }

        @Override
        protected void nodeOperation(AllocatedPersistentTask task, JobParams params) {
            JobTask jobTask = (JobTask) task;
            jobTask.autodetectProcessManager = autodetectProcessManager;
            autodetectProcessManager.openJob(jobTask, params.isIgnoreDowntime(), e2 -> {
                if (e2 == null) {
                    task.markAsCompleted();
                } else {
                    task.markAsFailed(e2);
                }
            });
        }

        @Override
        protected AllocatedPersistentTask createTask(long id, String type, String action, TaskId parentTaskId,
                                                     PersistentTask<JobParams> persistentTask) {
             return new JobTask(persistentTask.getParams().getJobId(), id, type, action, parentTaskId);
        }

        void setMaxConcurrentJobAllocations(int maxConcurrentJobAllocations) {
            logger.info("Changing [{}] from [{}] to [{}]", MachineLearning.CONCURRENT_JOB_ALLOCATIONS.getKey(),
                    this.maxConcurrentJobAllocations, maxConcurrentJobAllocations);
            this.maxConcurrentJobAllocations = maxConcurrentJobAllocations;
        }
    }

    /**
     * Fail fast before trying to update the job state on master node if the job doesn't exist or its state
     * is not what it should be.
     */
    static void validate(String jobId, MlMetadata mlMetadata) {
        Job job = mlMetadata.getJobs().get(jobId);
        if (job == null) {
            throw ExceptionsHelper.missingJobException(jobId);
        }
        if (job.isDeleted()) {
            throw ExceptionsHelper.conflictStatusException("Cannot open job [" + jobId + "] because it has been marked as deleted");
        }
    }

    static Assignment selectLeastLoadedMlNode(String jobId, ClusterState clusterState, int maxConcurrentJobAllocations,
                                              long maxNumberOfOpenJobs, Logger logger) {
        List<String> unavailableIndices = verifyIndicesPrimaryShardsAreActive(jobId, clusterState);
        if (unavailableIndices.size() != 0) {
            String reason = "Not opening job [" + jobId + "], because not all primary shards are active for the following indices [" +
                    String.join(",", unavailableIndices) + "]";
            logger.debug(reason);
            return new Assignment(null, reason);
        }

        long maxAvailable = Long.MIN_VALUE;
        List<String> reasons = new LinkedList<>();
        DiscoveryNode minLoadedNode = null;
        PersistentTasksCustomMetaData persistentTasks = clusterState.getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
        for (DiscoveryNode node : clusterState.getNodes()) {
            Map<String, String> nodeAttributes = node.getAttributes();
            String enabled = nodeAttributes.get(MachineLearning.ML_ENABLED_NODE_ATTR);
            if (Boolean.valueOf(enabled) == false) {
                String reason = "Not opening job [" + jobId + "] on node [" + node + "], because this node isn't a ml node.";
                logger.trace(reason);
                reasons.add(reason);
                continue;
            }

            long numberOfAssignedJobs;
            int numberOfAllocatingJobs;
            if (persistentTasks != null) {
                numberOfAssignedJobs = persistentTasks.getNumberOfTasksOnNode(node.getId(), OpenJobAction.TASK_NAME);
                numberOfAllocatingJobs = persistentTasks.findTasks(OpenJobAction.TASK_NAME, task -> {
                    if (node.getId().equals(task.getExecutorNode()) == false) {
                        return false;
                    }
                    JobTaskStatus jobTaskState = (JobTaskStatus) task.getStatus();
                    return jobTaskState == null || // executor node didn't have the chance to set job status to OPENING
                           jobTaskState.isStatusStale(task); // previous executor node failed and
                    // current executor node didn't have the chance to set job status to OPENING
                }).size();
            } else {
                numberOfAssignedJobs = 0;
                numberOfAllocatingJobs = 0;
            }
            if (numberOfAllocatingJobs >= maxConcurrentJobAllocations) {
                String reason = "Not opening job [" + jobId + "] on node [" + node + "], because node exceeds [" + numberOfAllocatingJobs +
                        "] the maximum number of jobs [" + maxConcurrentJobAllocations + "] in opening state";
                logger.trace(reason);
                reasons.add(reason);
                continue;
            }

            long available = maxNumberOfOpenJobs - numberOfAssignedJobs;
            if (available == 0) {
                String reason = "Not opening job [" + jobId + "] on node [" + node + "], because this node is full. " +
                        "Number of opened jobs [" + numberOfAssignedJobs + "], " + MAX_RUNNING_JOBS_PER_NODE.getKey() +
                        " [" + maxNumberOfOpenJobs + "]";
                logger.trace(reason);
                reasons.add(reason);
                continue;
            }

            if (maxAvailable < available) {
                maxAvailable = available;
                minLoadedNode = node;
            }
        }
        if (minLoadedNode != null) {
            logger.debug("selected node [{}] for job [{}]", minLoadedNode, jobId);
            return new Assignment(minLoadedNode.getId(), "");
        } else {
            String explanation = String.join("|", reasons);
            logger.debug("no node selected for job [{}], reasons [{}]", jobId, explanation);
            return new Assignment(null, explanation);
        }
    }

    static String[] indicesOfInterest(ClusterState clusterState, String job) {
        String jobResultIndex = AnomalyDetectorsIndex.getPhysicalIndexFromState(clusterState, job);
        return new String[]{AnomalyDetectorsIndex.jobStateIndexName(), jobResultIndex, AnomalyDetectorsIndex.ML_META_INDEX};
    }

    static List<String> verifyIndicesPrimaryShardsAreActive(String jobId, ClusterState clusterState) {
        String[] indices = indicesOfInterest(clusterState, jobId);
        List<String> unavailableIndices = new ArrayList<>(indices.length);
        for (String index : indices) {
            // Indices are created on demand from templates.
            // It is not an error if the index doesn't exist yet
            if (clusterState.metaData().hasIndex(index) == false) {
                continue;
            }
            IndexRoutingTable routingTable = clusterState.getRoutingTable().index(index);
            if (routingTable == null || routingTable.allPrimaryShardsActive() == false) {
                unavailableIndices.add(index);
            }
        }
        return unavailableIndices;
    }
}
