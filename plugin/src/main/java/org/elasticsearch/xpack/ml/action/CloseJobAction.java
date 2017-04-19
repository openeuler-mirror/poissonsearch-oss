/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.TaskOperationFailure;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.tasks.BaseTasksRequest;
import org.elasticsearch.action.support.tasks.BaseTasksResponse;
import org.elasticsearch.action.support.tasks.TransportTasksAction;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.AtomicArray;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.discovery.MasterNotDiscoveredException;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.MachineLearning;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.config.JobState;
import org.elasticsearch.xpack.ml.job.config.JobTaskStatus;
import org.elasticsearch.xpack.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.notifications.Auditor;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData;
import org.elasticsearch.xpack.persistent.PersistentTasksCustomMetaData.PersistentTask;
import org.elasticsearch.xpack.persistent.PersistentTasksService;
import org.elasticsearch.xpack.security.InternalClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class CloseJobAction extends Action<CloseJobAction.Request, CloseJobAction.Response, CloseJobAction.RequestBuilder> {

    public static final CloseJobAction INSTANCE = new CloseJobAction();
    public static final String NAME = "cluster:admin/xpack/ml/job/close";

    private CloseJobAction() {
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

    public static class Request extends BaseTasksRequest<Request> implements ToXContent {

        public static final ParseField TIMEOUT = new ParseField("timeout");
        public static final ParseField FORCE = new ParseField("force");
        public static ObjectParser<Request, Void> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString(Request::setJobId, Job.ID);
            PARSER.declareString((request, val) ->
                    request.setCloseTimeout(TimeValue.parseTimeValue(val, TIMEOUT.getPreferredName())), TIMEOUT);
            PARSER.declareBoolean(Request::setForce, FORCE);
        }

        public static Request parseRequest(String jobId, XContentParser parser) {
            Request request = PARSER.apply(parser, null);
            if (jobId != null) {
                request.setJobId(jobId);
            }
            return request;
        }

        private String jobId;
        private boolean force = false;
        // A big state can take a while to persist.  For symmetry with the _open endpoint any
        // changes here should be reflected there too.
        private TimeValue timeout = MachineLearning.STATE_PERSIST_RESTORE_TIMEOUT;

        String[] resolvedJobIds;

        Request() {}

        public Request(String jobId) {
            this.jobId = jobId;
            // the default implementation just returns 1 jobId
            this.resolvedJobIds = new String[] { jobId };
        }

        public String getJobId() {
            return jobId;
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
            this.resolvedJobIds = new String[] { jobId };
        }

        public TimeValue getCloseTimeout() {
            return timeout;
        }

        public void setCloseTimeout(TimeValue timeout) {
            this.timeout = timeout;
        }

        public boolean isForce() {
            return force;
        }

        public void setForce(boolean force) {
            this.force = force;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobId = in.readString();
            timeout = new TimeValue(in);
            force = in.readBoolean();
            resolvedJobIds = in.readStringArray();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(jobId);
            timeout.writeTo(out);
            out.writeBoolean(force);
            out.writeStringArray(resolvedJobIds);
        }

        @Override
        public boolean match(Task task) {
            for (String id : resolvedJobIds) {
                if (OpenJobAction.JobTask.match(task, id)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Job.ID.getPreferredName(), jobId);
            builder.field(TIMEOUT.getPreferredName(), timeout.getStringRep());
            builder.field(FORCE.getPreferredName(), force);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, timeout, force);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || obj.getClass() != getClass()) {
                return false;
            }
            Request other = (Request) obj;
            return Objects.equals(jobId, other.jobId) &&
                    Objects.equals(timeout, other.timeout) &&
                    Objects.equals(force, other.force);
        }
    }

    static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        RequestBuilder(ElasticsearchClient client, CloseJobAction action) {
            super(client, action, new Request());
        }
    }

    public static class Response extends BaseTasksResponse implements Writeable, ToXContentObject {

        private boolean closed;

        Response() {
        }

        Response(StreamInput in) throws IOException {
            readFrom(in);
        }

        Response(boolean closed) {
            super(null, null);
            this.closed = closed;
        }

        public boolean isClosed() {
            return closed;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            closed = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeBoolean(closed);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("closed", closed);
            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Response response = (Response) o;
            return closed == response.closed;
        }

        @Override
        public int hashCode() {
            return Objects.hash(closed);
        }
    }

    public static class TransportAction extends TransportTasksAction<OpenJobAction.JobTask, Request, Response, Response> {

        private final InternalClient client;
        private final ClusterService clusterService;
        private final Auditor auditor;
        private final PersistentTasksService persistentTasksService;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ThreadPool threadPool,
                               ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                               ClusterService clusterService, InternalClient client,
                               Auditor auditor, PersistentTasksService persistentTasksService) {
            // We fork in innerTaskOperation(...), so we can use ThreadPool.Names.SAME here:
            super(settings, CloseJobAction.NAME, threadPool, clusterService, transportService, actionFilters,
                    indexNameExpressionResolver, Request::new, Response::new, ThreadPool.Names.SAME);
            this.client = client;
            this.clusterService = clusterService;
            this.auditor = auditor;
            this.persistentTasksService = persistentTasksService;
        }

        @Override
        protected void doExecute(Task task, Request request, ActionListener<Response> listener) {
            final ClusterState state = clusterService.state();
            final DiscoveryNodes nodes = state.nodes();
            if (nodes.isLocalNodeElectedMaster() == false) {
                // Delegates close job to elected master node, so it becomes the coordinating node.
                // See comment in OpenJobAction.Transport class for more information.
                if (nodes.getMasterNode() == null) {
                    listener.onFailure(new MasterNotDiscoveredException("no known master node"));
                } else {
                    transportService.sendRequest(nodes.getMasterNode(), actionName, request,
                            new ActionListenerResponseHandler<>(listener, Response::new));
                }
            } else {
                /*
                 * Closing of multiple jobs:
                 *
                 * 1. Resolve and validate jobs first: if any job does not meet the
                 * criteria (e.g. open datafeed), fail immediately, do not close any
                 * job
                 *
                 * 2. Internally a task request is created for every job, so there
                 * are n inner tasks for 1 user request
                 *
                 * 3. Collect n inner task results or failures and send 1 outer
                 * result/failure
                 */
                request.resolvedJobIds = resolveAndValidateJobId(request.getJobId(), state).toArray(new String[0]);
                if (request.resolvedJobIds.length == 0) {
                    listener.onResponse(new Response(true));
                    return;
                }

                Set<String> executorNodes = new HashSet<>();
                for (String resolvedJobId : request.resolvedJobIds) {
                    JobManager.getJobOrThrowIfUnknown(state, resolvedJobId);
                    PersistentTasksCustomMetaData tasks = state.metaData().custom(PersistentTasksCustomMetaData.TYPE);
                    PersistentTasksCustomMetaData.PersistentTask<?> jobTask = MlMetadata.getJobTask(resolvedJobId, tasks);
                    if (jobTask == null || jobTask.isAssigned() == false) {
                        String message = "Cannot perform requested action because job [" + resolvedJobId
                                + "] is not open";
                        listener.onFailure(ExceptionsHelper.conflictStatusException(message));
                        return;
                    } else {
                        executorNodes.add(jobTask.getExecutorNode());
                    }
                }

                request.setNodes(executorNodes.toArray(new String[executorNodes.size()]));
                if (request.isForce()) {
                    forceCloseJob(state, request, listener);
                } else {
                    normalCloseJob(state, task, request, listener);
                }
            }
        }

        @Override
        protected void taskOperation(Request request, OpenJobAction.JobTask jobTask, ActionListener<Response> listener) {
            JobTaskStatus taskStatus = new JobTaskStatus(JobState.CLOSING, jobTask.getAllocationId());
            jobTask.updatePersistentStatus(taskStatus, ActionListener.wrap(task -> {
                // we need to fork because we are now on a network threadpool and closeJob method may take a while to complete:
                threadPool.executor(ThreadPool.Names.MANAGEMENT).execute(new AbstractRunnable() {
                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }

                    @Override
                    protected void doRun() throws Exception {
                        jobTask.closeJob("close job (api)");
                        listener.onResponse(new Response(true));
                    }
                });
            }, listener::onFailure));
        }

        @Override
        protected boolean accumulateExceptions() {
            return true;
        }

        @Override
        protected Response newResponse(Request request, List<Response> tasks,
                List<TaskOperationFailure> taskOperationFailures,
                List<FailedNodeException> failedNodeExceptions) {

            // number of resolved jobs should be equal to the number of tasks,
            // otherwise something went wrong
            if (request.resolvedJobIds.length != tasks.size()) {
                if (taskOperationFailures.isEmpty() == false) {
                    throw org.elasticsearch.ExceptionsHelper
                            .convertToElastic(taskOperationFailures.get(0).getCause());
                } else if (failedNodeExceptions.isEmpty() == false) {
                    throw org.elasticsearch.ExceptionsHelper
                            .convertToElastic(failedNodeExceptions.get(0));
                } else {
                    // This can happen we the actual task in the node no longer exists,
                    // which means the job(s) have already been closed.
                    return new Response(true);
                }
            }

            return new Response(tasks.stream().allMatch(Response::isClosed));
        }

        @Override
        protected Response readTaskResponse(StreamInput in) throws IOException {
            return new Response(in);
        }

        private void forceCloseJob(ClusterState currentState, Request request,
                ActionListener<Response> listener) {

            final int numberOfJobs = request.resolvedJobIds.length;
            final AtomicInteger counter = new AtomicInteger();
            final AtomicArray<Exception> failures = new AtomicArray<>(numberOfJobs);
            for (String jobId : request.resolvedJobIds) {
                auditor.info(jobId, Messages.JOB_AUDIT_FORCE_CLOSING);
                PersistentTask<?> jobTask = validateAndReturnJobTask(jobId, currentState);
                persistentTasksService.cancelPersistentTask(jobTask.getId(),
                        new ActionListener<PersistentTask<?>>() {
                            @Override
                            public void onResponse(PersistentTask<?> task) {
                                if (counter.incrementAndGet() == numberOfJobs) {
                                    sendResponseOrFailure(request.getJobId(), listener, failures);
                                }
                            }

                            @Override
                            public void onFailure(Exception e) {
                                final int slot = counter.incrementAndGet();
                                failures.set(slot - 1, e);
                                if (slot == numberOfJobs) {
                                    sendResponseOrFailure(request.getJobId(), listener, failures);
                                }
                            }

                            private void sendResponseOrFailure(String jobId,
                                    ActionListener<Response> listener,
                                    AtomicArray<Exception> failures) {
                                List<Exception> catchedExceptions = failures.asList();
                                if (catchedExceptions.size() == 0) {
                                    listener.onResponse(new Response(true));
                                    return;
                                }

                                String msg = "Failed to force close job [" + jobId + "] with ["
                                        + catchedExceptions.size()
                                        + "] failures, rethrowing last, all Exceptions: ["
                                        + catchedExceptions.stream().map(Exception::getMessage)
                                                .collect(Collectors.joining(", "))
                                        + "]";

                                ElasticsearchException e = new ElasticsearchException(msg,
                                        catchedExceptions.get(0));
                                listener.onFailure(e);
                            }
                        });
            }
        }

        private void normalCloseJob(ClusterState currentState, Task task, Request request,
                ActionListener<Response> listener) {
            Map<String, String> jobIdToPersistentTaskId = new HashMap<>();

            for (String jobId : request.resolvedJobIds) {
                auditor.info(jobId, Messages.JOB_AUDIT_CLOSING);
                PersistentTask<?> jobTask = validateAndReturnJobTask(jobId, currentState);
                jobIdToPersistentTaskId.put(jobId, jobTask.getId());
            }

            ActionListener<Response> finalListener =
                    ActionListener.wrap(
                            r -> waitForJobClosed(request, jobIdToPersistentTaskId,
                            r, listener),
                            listener::onFailure);
            super.doExecute(task, request, finalListener);
        }

        // Wait for job to be marked as closed in cluster state, which means the job persistent task has been removed
        // This api returns when job has been closed, but that doesn't mean the persistent task has been removed from cluster state,
        // so wait for that to happen here.
        void waitForJobClosed(Request request, Map<String, String> jobIdToPersistentTaskId, Response response,
                ActionListener<Response> listener) {
            persistentTasksService.waitForPersistentTasksStatus(persistentTasksCustomMetaData -> {
                for (Map.Entry<String, String> entry : jobIdToPersistentTaskId.entrySet()) {
                    String persistentTaskId = entry.getValue();
                    if (persistentTasksCustomMetaData.getTask(persistentTaskId) != null) {
                        return false;
                    }
                }
                return true;
            }, request.timeout, new ActionListener<Boolean>() {
                @Override
                public void onResponse(Boolean result) {
                    Set<String> jobIds = jobIdToPersistentTaskId.keySet();
                    FinalizeJobExecutionAction.Request finalizeRequest = new FinalizeJobExecutionAction.Request(
                            jobIds.toArray(new String[jobIds.size()]));
                    client.execute(FinalizeJobExecutionAction.INSTANCE, finalizeRequest,
                            new ActionListener<FinalizeJobExecutionAction.Response>() {
                                @Override
                                public void onResponse(FinalizeJobExecutionAction.Response r) {
                                    listener.onResponse(response);
                                }

                                @Override
                                public void onFailure(Exception e) {
                                    listener.onFailure(e);
                                }
                            });
                }

                @Override
                public void onFailure(Exception e) {
                    listener.onFailure(e);
                }
            });
        }
    }

    static List<String> resolveAndValidateJobId(String jobId, ClusterState state) {
        MlMetadata mlMetadata = state.metaData().custom(MlMetadata.TYPE);
        PersistentTasksCustomMetaData tasks = state.getMetaData()
                .custom(PersistentTasksCustomMetaData.TYPE);

        if (!Job.ALL.equals(jobId)) {
            validateAndReturnJobTask(jobId, state);
            return Collections.singletonList(jobId);
        }

        if (mlMetadata.getJobs().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> matchedJobs = new ArrayList<>();

        for (Map.Entry<String, Job> jobEntry : mlMetadata.getJobs().entrySet()) {
            String resolvedJobId = jobEntry.getKey();
            Job job = jobEntry.getValue();

            if (job.isDeleted()) {
                continue;
            }

            PersistentTask<?> jobTask = MlMetadata.getJobTask(resolvedJobId, tasks);
            if (jobTask == null) {
                continue;
            }

            if (MlMetadata.getJobState(resolvedJobId, tasks).isAnyOf(JobState.OPENED, JobState.FAILED) == false) {
                continue;
            }

            validateAndReturnJobTask(resolvedJobId, state);

            matchedJobs.add(resolvedJobId);
        }

        return matchedJobs;
    }

    static PersistentTask<?> validateAndReturnJobTask(String jobId, ClusterState state) {
        MlMetadata mlMetadata = state.metaData().custom(MlMetadata.TYPE);
        Job job = mlMetadata.getJobs().get(jobId);
        if (job == null) {
            throw new ResourceNotFoundException("cannot close job, because job [" + jobId + "] does not exist");
        }

        PersistentTasksCustomMetaData tasks = state.getMetaData()
                .custom(PersistentTasksCustomMetaData.TYPE);
        PersistentTask<?> jobTask = MlMetadata.getJobTask(jobId, tasks);
        if (jobTask == null || jobTask.getStatus() == null) {
            throw new ElasticsearchStatusException("cannot close job, because job [" + jobId + "] is not open", RestStatus.CONFLICT);
        }
        JobTaskStatus jobTaskStatus = (JobTaskStatus) jobTask.getStatus();
        if (jobTaskStatus.getState().isAnyOf(JobState.OPENED, JobState.FAILED) == false) {
            throw new ElasticsearchStatusException("cannot close job, because job [" + jobId + "] is not open", RestStatus.CONFLICT);
        }

        Optional<DatafeedConfig> datafeed = mlMetadata.getDatafeedByJobId(jobId);
        if (datafeed.isPresent()) {
            DatafeedState datafeedState = MlMetadata.getDatafeedState(datafeed.get().getId(),
                    tasks);
            if (datafeedState != DatafeedState.STOPPED) {
                throw new ElasticsearchStatusException(
                        "cannot close job [{}], datafeed hasn't been stopped", RestStatus.CONFLICT,
                        jobId);
            }
        }
        return jobTask;
    }
}

