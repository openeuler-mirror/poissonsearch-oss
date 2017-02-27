/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

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
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.MlMetadata;
import org.elasticsearch.xpack.ml.action.GetDatafeedsStatsAction.Response.DatafeedStats;
import org.elasticsearch.xpack.ml.action.util.QueryPage;
import org.elasticsearch.xpack.ml.datafeed.DatafeedConfig;
import org.elasticsearch.xpack.ml.datafeed.DatafeedState;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;
import org.elasticsearch.xpack.persistent.PersistentTasks;
import org.elasticsearch.xpack.persistent.PersistentTasks.PersistentTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class GetDatafeedsStatsAction extends Action<GetDatafeedsStatsAction.Request, GetDatafeedsStatsAction.Response,
        GetDatafeedsStatsAction.RequestBuilder> {

    public static final GetDatafeedsStatsAction INSTANCE = new GetDatafeedsStatsAction();
    public static final String NAME = "cluster:admin/ml/datafeeds/stats/get";

    public static final String ALL = "_all";
    private static final String STATE = "state";

    private GetDatafeedsStatsAction() {
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

        private String datafeedId;

        public Request(String datafeedId) {
            this.datafeedId = ExceptionsHelper.requireNonNull(datafeedId, DatafeedConfig.ID.getPreferredName());
        }

        Request() {}

        public String getDatafeedId() {
            return datafeedId;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            datafeedId = in.readString();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(datafeedId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(datafeedId);
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
            return Objects.equals(datafeedId, other.datafeedId);
        }
    }

    public static class RequestBuilder extends MasterNodeReadOperationRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client, GetDatafeedsStatsAction action) {
            super(client, action, new Request());
        }
    }

    public static class Response extends ActionResponse implements ToXContentObject {

        public static class DatafeedStats implements ToXContent, Writeable {

            private final String datafeedId;
            private final DatafeedState datafeedState;
            @Nullable
            private DiscoveryNode node;
            @Nullable
            private String assignmentExplanation;

            DatafeedStats(String datafeedId, DatafeedState datafeedState, @Nullable DiscoveryNode node,
                          @Nullable String assignmentExplanation) {
                this.datafeedId = Objects.requireNonNull(datafeedId);
                this.datafeedState = Objects.requireNonNull(datafeedState);
                this.node = node;
                this.assignmentExplanation = assignmentExplanation;
            }

            DatafeedStats(StreamInput in) throws IOException {
                datafeedId = in.readString();
                datafeedState = DatafeedState.fromStream(in);
                node = in.readOptionalWriteable(DiscoveryNode::new);
                assignmentExplanation = in.readOptionalString();
            }

            public String getDatafeedId() {
                return datafeedId;
            }

            public DatafeedState getDatafeedState() {
                return datafeedState;
            }

            public DiscoveryNode getNode() {
                return node;
            }

            public String getAssignmentExplanation() {
                return assignmentExplanation;
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                builder.startObject();
                builder.field(DatafeedConfig.ID.getPreferredName(), datafeedId);
                builder.field(STATE, datafeedState.toString());
                if (node != null) {
                    builder.startObject("node");
                    builder.field("id", node.getId());
                    builder.field("name", node.getName());
                    builder.field("ephemeral_id", node.getEphemeralId());
                    builder.field("transport_address", node.getAddress().toString());

                    builder.startObject("attributes");
                    for (Map.Entry<String, String> entry : node.getAttributes().entrySet()) {
                        builder.field(entry.getKey(), entry.getValue());
                    }
                    builder.endObject();
                    builder.endObject();
                }
                if (assignmentExplanation != null) {
                    builder.field("assigment_explanation", assignmentExplanation);
                }
                builder.endObject();
                return builder;
            }

            @Override
            public void writeTo(StreamOutput out) throws IOException {
                out.writeString(datafeedId);
                datafeedState.writeTo(out);
                out.writeOptionalWriteable(node);
                out.writeOptionalString(assignmentExplanation);
            }

            @Override
            public int hashCode() {
                return Objects.hash(datafeedId, datafeedState, node, assignmentExplanation);
            }

            @Override
            public boolean equals(Object obj) {
                if (obj == null) {
                    return false;
                }
                if (getClass() != obj.getClass()) {
                    return false;
                }
                DatafeedStats other = (DatafeedStats) obj;
                return Objects.equals(datafeedId, other.datafeedId) &&
                        Objects.equals(this.datafeedState, other.datafeedState) &&
                        Objects.equals(this.node, other.node) &&
                        Objects.equals(this.assignmentExplanation, other.assignmentExplanation);
            }
        }

        private QueryPage<DatafeedStats> datafeedsStats;

        public Response(QueryPage<DatafeedStats> datafeedsStats) {
            this.datafeedsStats = datafeedsStats;
        }

        public Response() {}

        public QueryPage<DatafeedStats> getResponse() {
            return datafeedsStats;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            datafeedsStats = new QueryPage<>(in, DatafeedStats::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            datafeedsStats.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            datafeedsStats.doXContentBody(builder, params);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(datafeedsStats);
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
            return Objects.equals(datafeedsStats, other.datafeedsStats);
        }

        @Override
        public final String toString() {
            return Strings.toString(this);
        }
    }

    public static class TransportAction extends TransportMasterNodeReadAction<Request, Response> {

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ClusterService clusterService,
                               ThreadPool threadPool, ActionFilters actionFilters,
                               IndexNameExpressionResolver indexNameExpressionResolver) {
            super(settings, GetDatafeedsStatsAction.NAME, transportService, clusterService, threadPool, actionFilters,
                    indexNameExpressionResolver, Request::new);
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
            logger.debug("Get stats for datafeed '{}'", request.getDatafeedId());

            Map<String, DatafeedStats> results = new HashMap<>();
            MlMetadata mlMetadata = state.metaData().custom(MlMetadata.TYPE);
            PersistentTasks tasksInProgress = state.getMetaData().custom(PersistentTasks.TYPE);
            if (request.getDatafeedId().equals(ALL) == false && mlMetadata.getDatafeed(request.getDatafeedId()) == null) {
                throw ExceptionsHelper.missingDatafeedException(request.getDatafeedId());
            }

            for (DatafeedConfig datafeedConfig : mlMetadata.getDatafeeds().values()) {
                if (request.getDatafeedId().equals(ALL) || datafeedConfig.getId().equals(request.getDatafeedId())) {
                    PersistentTask<?> task = MlMetadata.getDatafeedTask(request.getDatafeedId(), tasksInProgress);
                    DatafeedState datafeedState = MlMetadata.getDatafeedState(request.getDatafeedId(), tasksInProgress);
                    DiscoveryNode node = null;
                    String explanation = null;
                    if (task != null) {
                        node = state.nodes().get(task.getExecutorNode());
                        explanation = task.getAssignment().getExplanation();
                    }
                    results.put(datafeedConfig.getId(), new DatafeedStats(datafeedConfig.getId(), datafeedState, node, explanation));
                }
            }
            QueryPage<DatafeedStats> statsPage = new QueryPage<>(new ArrayList<>(results.values()), results.size(),
                    DatafeedConfig.RESULTS_FIELD);
            listener.onResponse(new Response(statsPage));
        }

        @Override
        protected ClusterBlockException checkBlock(Request request, ClusterState state) {
            return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
        }
    }
}
