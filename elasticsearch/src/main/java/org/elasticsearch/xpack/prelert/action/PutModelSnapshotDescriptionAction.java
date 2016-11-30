/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
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
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.ModelSnapshot;
import org.elasticsearch.xpack.prelert.job.manager.JobManager;
import org.elasticsearch.xpack.prelert.job.messages.Messages;
import org.elasticsearch.xpack.prelert.job.persistence.ElasticsearchJobProvider;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;
import org.elasticsearch.xpack.prelert.utils.SingleDocument;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public class PutModelSnapshotDescriptionAction extends
Action<PutModelSnapshotDescriptionAction.Request, PutModelSnapshotDescriptionAction.Response,
PutModelSnapshotDescriptionAction.RequestBuilder> {

    public static final PutModelSnapshotDescriptionAction INSTANCE = new PutModelSnapshotDescriptionAction();
    public static final String NAME = "cluster:admin/prelert/modelsnapshot/put/description";

    private PutModelSnapshotDescriptionAction() {
        super(NAME);
    }

    @Override
    public PutModelSnapshotDescriptionAction.RequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new RequestBuilder(client, this);
    }

    @Override
    public PutModelSnapshotDescriptionAction.Response newResponse() {
        return new Response();
    }

    public static class Request extends ActionRequest implements ToXContent {

        private static final ObjectParser<Request, ParseFieldMatcherSupplier> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString((request, jobId) -> request.jobId = jobId, Job.ID);
            PARSER.declareString((request, snapshotId) -> request.snapshotId = snapshotId, ModelSnapshot.SNAPSHOT_ID);
            PARSER.declareString((request, description) -> request.description = description, ModelSnapshot.DESCRIPTION);
        }

        public static Request parseRequest(String jobId, String snapshotId, XContentParser parser,
                ParseFieldMatcherSupplier parseFieldMatcherSupplier) {
            Request request = PARSER.apply(parser, parseFieldMatcherSupplier);
            if (jobId != null) {
                request.jobId = jobId;
            }
            if (snapshotId != null) {
                request.snapshotId = snapshotId;
            }
            return request;
        }

        private String jobId;
        private String snapshotId;
        private String description;

        Request() {
        }

        public Request(String jobId, String snapshotId, String description) {
            this.jobId = ExceptionsHelper.requireNonNull(jobId, Job.ID.getPreferredName());
            this.snapshotId = ExceptionsHelper.requireNonNull(snapshotId, ModelSnapshot.SNAPSHOT_ID.getPreferredName());
            this.description = ExceptionsHelper.requireNonNull(description, ModelSnapshot.DESCRIPTION.getPreferredName());
        }

        public String getJobId() {
            return jobId;
        }

        public String getSnapshotId() {
            return snapshotId;
        }

        public String getDescriptionString() {
            return description;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobId = in.readString();
            snapshotId = in.readString();
            description = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(jobId);
            out.writeString(snapshotId);
            out.writeString(description);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Job.ID.getPreferredName(), jobId);
            builder.field(ModelSnapshot.SNAPSHOT_ID.getPreferredName(), snapshotId);
            builder.field(ModelSnapshot.DESCRIPTION.getPreferredName(), description);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, snapshotId, description);
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
            return Objects.equals(jobId, other.jobId) && Objects.equals(snapshotId, other.snapshotId)
                    && Objects.equals(description, other.description);
        }
    }

    public static class Response extends ActionResponse implements StatusToXContent {

        private static final ParseField ACKNOWLEDGED = new ParseField("acknowledged");
        private static final ParseField MODEL = new ParseField("model");

        private ModelSnapshot model;

        Response() {

        }

        public Response(ModelSnapshot modelSnapshot) {
            model = modelSnapshot;
        }

        public ModelSnapshot getModel() {
            return model;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            model = new ModelSnapshot(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
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

    public static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client, PutModelSnapshotDescriptionAction action) {
            super(client, action, new Request());
        }
    }

    public static class TransportAction extends HandledTransportAction<Request, Response> {

        private final JobManager jobManager;
        private final ElasticsearchJobProvider jobProvider;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ThreadPool threadPool, ActionFilters actionFilters,
                IndexNameExpressionResolver indexNameExpressionResolver, JobManager jobManager, ElasticsearchJobProvider jobProvider) {
            super(settings, NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, Request::new);
            this.jobManager = jobManager;
            this.jobProvider = jobProvider;
        }

        @Override
        protected void doExecute(Request request, ActionListener<Response> listener) {

            logger.debug("Received request to change model snapshot description using '" + request.getDescriptionString()
            + "' for snapshot ID '" + request.getSnapshotId() + "' for job '" + request.getJobId() + "'");

            List<ModelSnapshot> changeCandidates = getChangeCandidates(request);
            checkForClashes(request);

            if (changeCandidates.size() > 1) {
                logger.warn("More than one model found for [jobId: " + request.getJobId() + ", snapshotId: " + request.getSnapshotId()
                + "] tuple.");
            }
            ModelSnapshot modelSnapshot = changeCandidates.get(0);
            modelSnapshot.setDescription(request.getDescriptionString());
            jobManager.updateModelSnapshot(request.getJobId(), modelSnapshot, false);

            modelSnapshot.setDescription(request.getDescriptionString());

            // The quantiles can be large, and totally dominate the output -
            // it's
            // clearer to remove them
            modelSnapshot.setQuantiles(null);

            listener.onResponse(new Response(modelSnapshot));

        }

        private List<ModelSnapshot> getChangeCandidates(Request request) {
            List<ModelSnapshot> changeCandidates = getModelSnapshots(request.getJobId(), request.getSnapshotId(), null);
            if (changeCandidates == null || changeCandidates.isEmpty()) {
                throw new ResourceNotFoundException(Messages.getMessage(Messages.REST_NO_SUCH_MODEL_SNAPSHOT, request.getJobId()));
            }
            return changeCandidates;
        }

        private void checkForClashes(Request request) {
            List<ModelSnapshot> clashCandidates = getModelSnapshots(request.getJobId(), null, request.getDescriptionString());
            if (clashCandidates != null && !clashCandidates.isEmpty()) {
                throw new IllegalArgumentException(Messages.getMessage(
                        Messages.REST_DESCRIPTION_ALREADY_USED, request.getDescriptionString(), request.getJobId()));
            }
        }

        private List<ModelSnapshot> getModelSnapshots(String jobId, String snapshotId, String description) {
            return jobProvider.modelSnapshots(jobId, 0, 1, null, null, null, true, snapshotId, description).results();
        }

    }

}
