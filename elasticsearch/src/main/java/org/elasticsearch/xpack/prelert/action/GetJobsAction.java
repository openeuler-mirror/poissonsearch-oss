/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.action;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.MasterNodeReadOperationRequestBuilder;
import org.elasticsearch.action.support.master.MasterNodeReadRequest;
import org.elasticsearch.action.support.master.TransportMasterNodeReadAction;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.StatusToXContent;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.JobStatus;
import org.elasticsearch.xpack.prelert.job.ModelSizeStats;
import org.elasticsearch.xpack.prelert.job.SchedulerState;
import org.elasticsearch.xpack.prelert.job.manager.AutodetectProcessManager;
import org.elasticsearch.xpack.prelert.job.manager.JobManager;
import org.elasticsearch.xpack.prelert.job.persistence.ElasticsearchJobProvider;
import org.elasticsearch.xpack.prelert.job.persistence.QueryPage;
import org.elasticsearch.xpack.prelert.job.results.PageParams;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public class GetJobsAction extends Action<GetJobsAction.Request, GetJobsAction.Response, GetJobsAction.RequestBuilder> {

    public static final GetJobsAction INSTANCE = new GetJobsAction();
    public static final String NAME = "cluster:admin/prelert/jobs/get";

    private GetJobsAction() {
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

    public static class Request extends MasterNodeReadRequest<Request> {

        public static final ObjectParser<Request, ParseFieldMatcherSupplier> PARSER = new ObjectParser<>(NAME, Request::new);
        public static final ParseField METRIC = new ParseField("metric");

        static {
            PARSER.declareString(Request::setJobId, Job.ID);
            PARSER.declareObject(Request::setPageParams, PageParams.PARSER, PageParams.PAGE);
            PARSER.declareString((request, metric) -> {
                Set<String> stats = Strings.splitStringByCommaToSet(metric);
                request.setStats(stats);
            }, METRIC);
        }

        private String jobId;
        private boolean config;
        private boolean dataCounts;
        private boolean modelSizeStats;
        private boolean schedulerStatus;
        private boolean status;
        private PageParams pageParams = null;

        public Request() {
        }

        public void setJobId(String jobId) {
            this.jobId = jobId;
        }

        public String getJobId() {
            return jobId;
        }

        public PageParams getPageParams() {
            return pageParams;
        }

        public void setPageParams(PageParams pageParams) {
            this.pageParams = ExceptionsHelper.requireNonNull(pageParams, PageParams.PAGE.getPreferredName());
        }

        public Request all() {
            config = true;
            dataCounts = true;
            modelSizeStats = true;
            schedulerStatus = true;
            status = true;
            return this;
        }

        public boolean config() {
            return config;
        }

        public Request config(boolean config) {
            this.config = config;
            return this;
        }

        public boolean dataCounts() {
            return dataCounts;
        }

        public Request dataCounts(boolean dataCounts) {
            this.dataCounts = dataCounts;
            return this;
        }

        public boolean modelSizeStats() {
            return modelSizeStats;
        }

        public Request modelSizeStats(boolean modelSizeStats) {
            this.modelSizeStats = modelSizeStats;
            return this;
        }

        public boolean schedulerStatus() {
            return schedulerStatus;
        }

        public Request schedulerStatus(boolean schedulerStatus) {
            this.schedulerStatus = schedulerStatus;
            return this;
        }

        public void setStats(Set<String> stats) {
            if (stats.contains("_all")) {
                all();
            } else {
                config(stats.contains("config"));
                dataCounts(stats.contains("data_counts"));
                modelSizeStats(stats.contains("model_size_stats"));
                schedulerStatus(stats.contains("scheduler_state"));
                status(stats.contains("status"));
            }
        }

        public boolean status() {
            return status;
        }

        public Request status(boolean status) {
            this.status = status;
            return this;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobId = in.readOptionalString();
            config = in.readBoolean();
            dataCounts = in.readBoolean();
            modelSizeStats = in.readBoolean();
            schedulerStatus = in.readBoolean();
            status = in.readBoolean();
            pageParams = in.readOptionalWriteable(PageParams::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeOptionalString(jobId);
            out.writeBoolean(config);
            out.writeBoolean(dataCounts);
            out.writeBoolean(modelSizeStats);
            out.writeBoolean(schedulerStatus);
            out.writeBoolean(status);
            out.writeOptionalWriteable(pageParams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, config, dataCounts, modelSizeStats, schedulerStatus, status, pageParams);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Request other = (Request) obj;
            return Objects.equals(jobId, other.jobId)
                    && this.config == other.config
                    && this.dataCounts == other.dataCounts
                    && this.modelSizeStats == other.modelSizeStats
                    && this.schedulerStatus == other.schedulerStatus
                    && this.status == other.status
                    && Objects.equals(this.pageParams, other.pageParams);
        }
    }

    public static class RequestBuilder extends MasterNodeReadOperationRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client, GetJobsAction action) {
            super(client, action, new Request());
        }
    }

    public static class Response extends ActionResponse implements StatusToXContent {

        static class JobInfo implements ToXContent, Writeable {
            @Nullable
            private Job jobConfig;
            @Nullable
            private DataCounts dataCounts;
            @Nullable
            private ModelSizeStats modelSizeStats;
            @Nullable
            private SchedulerState schedulerState;
            @Nullable
            private JobStatus status;



            JobInfo(@Nullable Job job, @Nullable DataCounts dataCounts, @Nullable ModelSizeStats modelSizeStats,
                    @Nullable SchedulerState schedulerStatus, @Nullable JobStatus status) {
                this.jobConfig = job;
                this.dataCounts = dataCounts;
                this.modelSizeStats = modelSizeStats;
                this.schedulerState = schedulerStatus;
                this.status = status;
            }

            JobInfo(StreamInput in) throws IOException {
                jobConfig = in.readOptionalWriteable(Job::new);
                dataCounts = in.readOptionalWriteable(DataCounts::new);
                modelSizeStats = in.readOptionalWriteable(ModelSizeStats::new);
                schedulerState = in.readOptionalWriteable(SchedulerState::new);
                status = in.readOptionalWriteable(JobStatus::fromStream);
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                builder.startObject();
                if (jobConfig != null) {
                    builder.field("config", jobConfig);
                }
                if (dataCounts != null) {
                    builder.field("data_counts", dataCounts);
                }
                if (modelSizeStats != null) {
                    builder.field("model_size_stats", modelSizeStats);
                }
                if (schedulerState != null) {
                    builder.field("scheduler_state", schedulerState);
                }
                if (status != null) {
                    builder.field("status", status);
                }
                builder.endObject();

                return builder;
            }

            @Override
            public void writeTo(StreamOutput out) throws IOException {
                out.writeOptionalWriteable(jobConfig);
                out.writeOptionalWriteable(dataCounts);
                out.writeOptionalWriteable(modelSizeStats);
                out.writeOptionalWriteable(schedulerState);
                out.writeOptionalWriteable(status);
            }

            @Override
            public int hashCode() {
                return Objects.hash(jobConfig, dataCounts, modelSizeStats, schedulerState, status);
            }

            @Override
            public boolean equals(Object obj) {
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                JobInfo other = (JobInfo) obj;
                return Objects.equals(jobConfig, other.jobConfig)
                        && Objects.equals(this.dataCounts, other.dataCounts)
                        && Objects.equals(this.modelSizeStats, other.modelSizeStats)
                        && Objects.equals(this.schedulerState, other.schedulerState)
                        && Objects.equals(this.status, other.status);
            }
        }

        private QueryPage<JobInfo> jobs;

        public Response(QueryPage<JobInfo> jobs) {
            this.jobs = jobs;
        }

        public Response() {}

        public QueryPage<JobInfo> getResponse() {
            return jobs;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobs = new QueryPage<>(in, JobInfo::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            jobs.writeTo(out);
        }

        @Override
        public RestStatus status() {
            return jobs.count() == 0 ? RestStatus.NOT_FOUND : RestStatus.OK;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return jobs.doXContentBody(builder, params);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobs);
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            Response other = (Response) obj;
            return Objects.equals(jobs, other.jobs);
        }

        @SuppressWarnings("deprecation")
        @Override
        public final String toString() {
            try {
                XContentBuilder builder = XContentFactory.jsonBuilder();
                builder.prettyPrint();
                builder.startObject();
                toXContent(builder, EMPTY_PARAMS);
                builder.endObject();
                return builder.string();
            } catch (Exception e) {
                // So we have a stack trace logged somewhere
                return "{ \"error\" : \"" + org.elasticsearch.ExceptionsHelper.detailedMessage(e) + "\"}";
            }
        }
    }


    public static class TransportAction extends TransportMasterNodeReadAction<Request, Response> {

        private final JobManager jobManager;
        private final AutodetectProcessManager processManager;
        private final ElasticsearchJobProvider jobProvider;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ClusterService clusterService,
                ThreadPool threadPool, ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                JobManager jobManager, AutodetectProcessManager processManager, ElasticsearchJobProvider jobProvider) {
            super(settings, GetJobsAction.NAME, transportService, clusterService, threadPool, actionFilters,
                    indexNameExpressionResolver, Request::new);
            this.jobManager = jobManager;
            this.processManager = processManager;
            this.jobProvider = jobProvider;
        }

        @Override
        protected String executor() {
            return ThreadPool.Names.SAME;
        }

        @Override
        protected Response newResponse() {
            return new Response();
        }

        @Override
        protected void masterOperation(Request request, ClusterState state, ActionListener<Response> listener) throws Exception {
            logger.debug("Get job '{}', config={}, data_counts={}, model_size_stats={}",
                    request.getJobId(), request.config(), request.dataCounts(), request.modelSizeStats());

            QueryPage<Response.JobInfo> response;

            // Single Job
            if (request.jobId != null && !request.jobId.isEmpty()) {
                // always get the job regardless of the request.config param because if the job
                // can't be found a different response is returned.
                QueryPage<Job> jobs = jobManager.getJob(request.getJobId(), state);
                if (jobs.count() == 0) {
                    logger.debug(String.format(Locale.ROOT, "Cannot find job '%s'", request.getJobId()));
                    throw QueryPage.emptyQueryPage(Job.RESULTS_FIELD);
                } else if (jobs.count() > 1) {
                    logger.error(String.format(Locale.ROOT, "More than one job found for jobId [%s]", request.getJobId()));
                }

                logger.debug("Returning job [" + request.getJobId() + "]");
                Job jobConfig = request.config() ? jobs.results().get(0) : null;
                DataCounts dataCounts = readDataCounts(request.dataCounts(), request.getJobId());
                ModelSizeStats modelSizeStats = readModelSizeStats(request.modelSizeStats(), request.getJobId());
                SchedulerState schedulerStatus = readSchedulerState(request.schedulerStatus(), request.getJobId());
                JobStatus jobStatus = readJobStatus(request.status(), request.getJobId());

                Response.JobInfo jobInfo = new Response.JobInfo(jobConfig, dataCounts, modelSizeStats, schedulerStatus, jobStatus);
                response = new QueryPage<>(Collections.singletonList(jobInfo), 1, Job.RESULTS_FIELD);

            } else {
                // Multiple Jobs
                QueryPage<Job> jobsPage = jobManager.getJobs(request.pageParams.getFrom(), request.pageParams.getSize(), state);
                List<Response.JobInfo> jobInfoList = new ArrayList<>();
                for (Job job : jobsPage.results()) {
                    Job jobConfig = request.config() ? job : null;
                    DataCounts dataCounts = readDataCounts(request.dataCounts(), job.getJobId());
                    ModelSizeStats modelSizeStats = readModelSizeStats(request.modelSizeStats(), job.getJobId());
                    SchedulerState schedulerStatus = readSchedulerState(request.schedulerStatus(), job.getJobId());
                    JobStatus jobStatus = readJobStatus(request.status(), job.getJobId());
                    Response.JobInfo jobInfo = new Response.JobInfo(jobConfig, dataCounts, modelSizeStats, schedulerStatus, jobStatus);
                    jobInfoList.add(jobInfo);
                }
                response = new QueryPage<>(jobInfoList, jobsPage.count(), Job.RESULTS_FIELD);
            }

            listener.onResponse(new Response(response));
        }

        @Override
        protected ClusterBlockException checkBlock(Request request, ClusterState state) {
            return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
        }

        private DataCounts readDataCounts(boolean dataCounts, String jobId) {
            if (dataCounts) {
                Optional<DataCounts> counts = processManager.getDataCounts(jobId);
                return counts.orElseGet(() -> jobProvider.dataCounts(jobId));
            }
            return null;
        }

        private ModelSizeStats readModelSizeStats(boolean modelSizeStats, String jobId) {
            if (modelSizeStats) {
                Optional<ModelSizeStats> sizeStats = processManager.getModelSizeStats(jobId);
                return sizeStats.orElseGet(() -> jobProvider.modelSizeStats(jobId).orElse(null));
            }
            return null;
        }

        private SchedulerState readSchedulerState(boolean schedulerState, String jobId) {
            return schedulerState ? jobManager.getSchedulerState(jobId).orElse(null) : null;
        }

        private JobStatus readJobStatus(boolean status, String jobId) {
            return status ? jobManager.getJobStatus(jobId) : null;
        }
    }

}
