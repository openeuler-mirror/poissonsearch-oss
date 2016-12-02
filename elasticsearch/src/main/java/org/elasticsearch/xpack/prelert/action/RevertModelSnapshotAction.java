/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.AcknowledgedRequest;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.action.support.master.MasterNodeOperationRequestBuilder;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.StatusToXContent;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.JobStatus;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.manager.JobManager;
import org.elasticsearch.xpack.prelert.job.messages.Messages;
import org.elasticsearch.xpack.prelert.job.metadata.Allocation;
import org.elasticsearch.xpack.prelert.job.persistence.ElasticsearchBulkDeleterFactory;
import org.elasticsearch.xpack.prelert.job.persistence.ElasticsearchJobProvider;
import org.elasticsearch.xpack.prelert.job.persistence.JobDataCountsPersister;
import org.elasticsearch.xpack.prelert.job.persistence.JobProvider;
import org.elasticsearch.xpack.prelert.job.persistence.OldDataRemover;
import org.elasticsearch.xpack.prelert.job.persistence.QueryPage;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class RevertModelSnapshotAction
extends Action<RevertModelSnapshotAction.Request, RevertModelSnapshotAction.Response, RevertModelSnapshotAction.RequestBuilder> {

    public static final RevertModelSnapshotAction INSTANCE = new RevertModelSnapshotAction();
    public static final String NAME = "indices:admin/prelert/modelsnapshots/revert";

    private RevertModelSnapshotAction() {
        super(NAME);
    }

    @Override
    public RequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new RequestBuilder(client);
    }

    @Override
    public Response newResponse() {
        return new Response();
    }

    public static class Request extends AcknowledgedRequest<Request> implements ToXContent {

        public static final ParseField TIME = new ParseField("time");
        public static final ParseField SNAPSHOT_ID = new ParseField("snapshotId");
        public static final ParseField DESCRIPTION = new ParseField("description");
        public static final ParseField DELETE_INTERVENING = new ParseField("deleteInterveningResults");

        private static ObjectParser<Request, ParseFieldMatcherSupplier> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString((request, jobId) -> request.jobId = jobId, Job.ID);
            PARSER.declareString(Request::setTime, TIME);
            PARSER.declareString(Request::setSnapshotId, SNAPSHOT_ID);
            PARSER.declareString(Request::setDescription, DESCRIPTION);
            PARSER.declareBoolean(Request::setDeleteInterveningResults, DELETE_INTERVENING);
        }

        public static Request parseRequest(String jobId, XContentParser parser, ParseFieldMatcherSupplier parseFieldMatcherSupplier) {
            Request request = PARSER.apply(parser, parseFieldMatcherSupplier);
            if (jobId != null) {
                request.jobId = jobId;
            }
            return request;
        }

        private String jobId;
        private String time;
        private String snapshotId;
        private String description;
        private boolean deleteInterveningResults;

        Request() {
        }

        public Request(String jobId) {
            this.jobId = ExceptionsHelper.requireNonNull(jobId, Job.ID.getPreferredName());
        }

        public String getJobId() {
            return jobId;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public String getSnapshotId() {
            return snapshotId;
        }

        public void setSnapshotId(String snapshotId) {
            this.snapshotId = snapshotId;
        }

        @Override
        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean getDeleteInterveningResults() {
            return deleteInterveningResults;
        }

        public void setDeleteInterveningResults(boolean deleteInterveningResults) {
            this.deleteInterveningResults = deleteInterveningResults;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;
            if (time == null && snapshotId == null && description == null) {
                validationException = addValidationError(Messages.getMessage(Messages.REST_INVALID_REVERT_PARAMS), validationException);
            }
            return validationException;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobId = in.readString();
            time = in.readOptionalString();
            snapshotId = in.readOptionalString();
            description = in.readOptionalString();
            deleteInterveningResults = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(jobId);
            out.writeOptionalString(time);
            out.writeOptionalString(snapshotId);
            out.writeOptionalString(description);
            out.writeBoolean(deleteInterveningResults);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Job.ID.getPreferredName(), jobId);
            if (time != null) {
                builder.field(TIME.getPreferredName(), time);
            }
            if (snapshotId != null) {
                builder.field(SNAPSHOT_ID.getPreferredName(), snapshotId);
            }
            if (description != null) {
                builder.field(DESCRIPTION.getPreferredName(), description);
            }
            builder.field(DELETE_INTERVENING.getPreferredName(), deleteInterveningResults);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, time, snapshotId, description, deleteInterveningResults);
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
            return Objects.equals(jobId, other.jobId) && Objects.equals(time, other.time) && Objects.equals(snapshotId, other.snapshotId)
                    && Objects.equals(description, other.description)
                    && Objects.equals(deleteInterveningResults, other.deleteInterveningResults);
        }
    }

    static class RequestBuilder extends MasterNodeOperationRequestBuilder<Request, Response, RequestBuilder> {

        RequestBuilder(ElasticsearchClient client) {
            super(client, INSTANCE, new Request());
        }
    }

    public static class Response extends AcknowledgedResponse implements StatusToXContent {

        private static final ParseField ACKNOWLEDGED = new ParseField("acknowledged");
        private static final ParseField MODEL = new ParseField("model");
        private ModelSnapshot model;

        Response() {

        }

        public Response(ModelSnapshot modelSnapshot) {
            super(true);
            model = modelSnapshot;
        }

        public ModelSnapshot getModel() {
            return model;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            readAcknowledged(in);
            model = new ModelSnapshot(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            writeAcknowledged(out);
            model.writeTo(out);
        }

        @Override
        public RestStatus status() {
            return RestStatus.OK;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field(ACKNOWLEDGED.getPreferredName(), true);
            builder.field(MODEL.getPreferredName());
            builder = model.toXContent(builder, params);
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(model);
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
            return Objects.equals(model, other.model);
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

    public static class TransportAction extends TransportMasterNodeAction<Request, Response> {

        private final JobManager jobManager;
        private final JobProvider jobProvider;
        private final ElasticsearchBulkDeleterFactory bulkDeleterFactory;
        private final JobDataCountsPersister jobDataCountsPersister;

        @Inject
        public TransportAction(Settings settings, ThreadPool threadPool, TransportService transportService, ActionFilters actionFilters,
                IndexNameExpressionResolver indexNameExpressionResolver, JobManager jobManager, ElasticsearchJobProvider jobProvider,
                ClusterService clusterService, ElasticsearchBulkDeleterFactory bulkDeleterFactory,
                JobDataCountsPersister jobDataCountsPersister) {
            super(settings, NAME, transportService, clusterService, threadPool, actionFilters, indexNameExpressionResolver, Request::new);
            this.jobManager = jobManager;
            this.jobProvider = jobProvider;
            this.bulkDeleterFactory = bulkDeleterFactory;
            this.jobDataCountsPersister = jobDataCountsPersister;
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
            logger.debug("Received request to revert to time '{}' description '{}' snapshot id '{}' for job '{}', deleting intervening " +
                            "results: {}",
                    request.getTime(), request.getDescription(), request.getSnapshotId(), request.getJobId(),
                    request.getDeleteInterveningResults());

            if (request.getTime() == null && request.getSnapshotId() == null && request.getDescription() == null) {
                throw new IllegalStateException(Messages.getMessage(Messages.REST_INVALID_REVERT_PARAMS));
            }

            QueryPage<Job> job = jobManager.getJob(request.getJobId(), clusterService.state());
            Allocation allocation = jobManager.getJobAllocation(request.getJobId());
            if (job.count() > 0 && allocation.getStatus().equals(JobStatus.CLOSED) == false) {
                throw ExceptionsHelper.conflictStatusException(Messages.getMessage(Messages.REST_JOB_NOT_CLOSED_REVERT));
            }

            ModelSnapshot modelSnapshot = getModelSnapshot(request, jobProvider);
            if (request.getDeleteInterveningResults()) {
                listener = wrapDeleteOldDataListener(listener, modelSnapshot, request.getJobId());
                listener = wrapRevertDataCountsListener(listener, modelSnapshot, request.getJobId());
            }
            jobManager.revertSnapshot(request, listener, modelSnapshot);
        }

        private ModelSnapshot getModelSnapshot(Request request, JobProvider provider) {
            logger.info("Reverting to snapshot '" + request.getSnapshotId() + "' for time '" + request.getTime() + "'");

            List<ModelSnapshot> revertCandidates;
            revertCandidates = provider.modelSnapshots(request.getJobId(), 0, 1, null, request.getTime(),
                    ModelSnapshot.TIMESTAMP.getPreferredName(), true, request.getSnapshotId(), request.getDescription()).results();

            if (revertCandidates == null || revertCandidates.isEmpty()) {
                throw new ResourceNotFoundException(Messages.getMessage(Messages.REST_NO_SUCH_MODEL_SNAPSHOT, request.getJobId()));
            }
            ModelSnapshot modelSnapshot = revertCandidates.get(0);

            // The quantiles can be large, and totally dominate the output -
            // it's clearer to remove them
            modelSnapshot.setQuantiles(null);
            return modelSnapshot;
        }

        private ActionListener<RevertModelSnapshotAction.Response> wrapDeleteOldDataListener(
                ActionListener<RevertModelSnapshotAction.Response> listener,
                ModelSnapshot modelSnapshot, String jobId) {

            // If we need to delete buckets that occurred after the snapshot, we
            // wrap the listener with one that invokes the OldDataRemover on
            // acknowledged responses
            return ActionListener.wrap(response -> {
                if (response.isAcknowledged()) {
                    Date deleteAfter = modelSnapshot.getLatestResultTimeStamp();
                    logger.debug("Removing intervening records: last record: " + deleteAfter + ", last result: "
                            + modelSnapshot.getLatestResultTimeStamp());

                    logger.info("Deleting results after '" + deleteAfter + "'");

                    // NORELEASE: OldDataRemover is basically delete-by-query.
                    // We should replace this
                    // whole abstraction with DBQ eventually
                    OldDataRemover remover = new OldDataRemover(bulkDeleterFactory);
                    remover.deleteResultsAfter(new ActionListener<BulkResponse>() {
                        @Override
                        public void onResponse(BulkResponse bulkItemResponses) {
                            listener.onResponse(response);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            listener.onFailure(e);
                        }
                    }, jobId, deleteAfter.getTime() + 1);
                }
            }, listener::onFailure);
        }

        private ActionListener<RevertModelSnapshotAction.Response> wrapRevertDataCountsListener(
                ActionListener<RevertModelSnapshotAction.Response> listener,
                ModelSnapshot modelSnapshot, String jobId) {


            return ActionListener.wrap(response -> {
                if (response.isAcknowledged()) {
                    DataCounts counts = jobProvider.dataCounts(jobId);
                    counts.setLatestRecordTimeStamp(modelSnapshot.getLatestRecordTimeStamp());
                    jobDataCountsPersister.persistDataCounts(jobId, counts, new ActionListener<Boolean>() {
                        @Override
                        public void onResponse(Boolean aBoolean) {
                            listener.onResponse(response);
                        }

                        @Override
                        public void onFailure(Exception e) {
                            listener.onFailure(e);
                        }
                    });
                }
            }, listener::onFailure);
        }

        @Override
        protected ClusterBlockException checkBlock(Request request, ClusterState state) {
            return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_WRITE);
        }
    }

}
