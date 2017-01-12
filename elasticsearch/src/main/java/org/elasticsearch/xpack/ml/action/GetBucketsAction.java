/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

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
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.job.Job;
import org.elasticsearch.xpack.ml.job.persistence.BucketsQueryBuilder;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.QueryPage;
import org.elasticsearch.xpack.ml.job.results.Bucket;
import org.elasticsearch.xpack.ml.job.results.PageParams;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Objects;

public class GetBucketsAction extends Action<GetBucketsAction.Request, GetBucketsAction.Response, GetBucketsAction.RequestBuilder> {

    public static final GetBucketsAction INSTANCE = new GetBucketsAction();
    public static final String NAME = "indices:admin/ml/results/buckets/get";

    private GetBucketsAction() {
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

    public static class Request extends ActionRequest implements ToXContent {

        public static final ParseField EXPAND = new ParseField("expand");
        public static final ParseField INCLUDE_INTERIM = new ParseField("include_interim");
        public static final ParseField PARTITION_VALUE = new ParseField("partition_value");
        public static final ParseField START = new ParseField("start");
        public static final ParseField END = new ParseField("end");
        public static final ParseField ANOMALY_SCORE = new ParseField("anomaly_score");
        public static final ParseField MAX_NORMALIZED_PROBABILITY = new ParseField("max_normalized_probability");
        public static final ParseField TIMESTAMP = new ParseField("timestamp");

        private static final ObjectParser<Request, ParseFieldMatcherSupplier> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString((request, jobId) -> request.jobId = jobId, Job.ID);
            PARSER.declareString(Request::setTimestamp, Bucket.TIMESTAMP);
            PARSER.declareString(Request::setPartitionValue, PARTITION_VALUE);
            PARSER.declareBoolean(Request::setExpand, EXPAND);
            PARSER.declareBoolean(Request::setIncludeInterim, INCLUDE_INTERIM);
            PARSER.declareStringOrNull(Request::setStart, START);
            PARSER.declareStringOrNull(Request::setEnd, END);
            PARSER.declareBoolean(Request::setExpand, EXPAND);
            PARSER.declareBoolean(Request::setIncludeInterim, INCLUDE_INTERIM);
            PARSER.declareObject(Request::setPageParams, PageParams.PARSER, PageParams.PAGE);
            PARSER.declareDouble(Request::setAnomalyScore, ANOMALY_SCORE);
            PARSER.declareDouble(Request::setMaxNormalizedProbability, MAX_NORMALIZED_PROBABILITY);
            PARSER.declareString(Request::setPartitionValue, PARTITION_VALUE);
        }

        public static Request parseRequest(String jobId, XContentParser parser,
                ParseFieldMatcherSupplier parseFieldMatcherSupplier) {
            Request request = PARSER.apply(parser, parseFieldMatcherSupplier);
            if (jobId != null) {
                request.jobId = jobId;
            }
            return request;
        }

        private String jobId;
        private String timestamp;
        private boolean expand = false;
        private boolean includeInterim = false;
        private String partitionValue;
        private String start;
        private String end;
        private PageParams pageParams;
        private Double anomalyScore;
        private Double maxNormalizedProbability;

        Request() {
        }

        public Request(String jobId) {
            this.jobId = ExceptionsHelper.requireNonNull(jobId, Job.ID.getPreferredName());
        }

        public String getJobId() {
            return jobId;
        }

        public void setTimestamp(String timestamp) {
            if (pageParams != null || start != null || end != null || anomalyScore != null || maxNormalizedProbability != null) {
                throw new IllegalArgumentException("Param [" + TIMESTAMP.getPreferredName() + "] is incompatible with ["
                                + PageParams.FROM.getPreferredName() + ","
                                + PageParams.SIZE.getPreferredName() + ","
                                + START.getPreferredName() + ","
                                + END.getPreferredName() + ","
                                + ANOMALY_SCORE.getPreferredName() + ","
                                + MAX_NORMALIZED_PROBABILITY.getPreferredName() + "]");
            }
            this.timestamp = ExceptionsHelper.requireNonNull(timestamp, Bucket.TIMESTAMP.getPreferredName());
        }

        public String getTimestamp() {
            return timestamp;
        }

        public boolean isExpand() {
            return expand;
        }

        public void setExpand(boolean expand) {
            this.expand = expand;
        }

        public boolean isIncludeInterim() {
            return includeInterim;
        }

        public void setIncludeInterim(boolean includeInterim) {
            this.includeInterim = includeInterim;
        }

        public String getPartitionValue() {
            return partitionValue;
        }

        public void setPartitionValue(String partitionValue) {
            if (timestamp != null) {
                throw new IllegalArgumentException("Param [" + PARTITION_VALUE.getPreferredName() + "] is incompatible with ["
                        + TIMESTAMP.getPreferredName() + "].");
            }
            this.partitionValue = partitionValue;
        }

        public String getStart() {
            return start;
        }

        public void setStart(String start) {
            if (timestamp != null) {
                throw new IllegalArgumentException("Param [" + START.getPreferredName() + "] is incompatible with ["
                        + TIMESTAMP.getPreferredName() + "].");
            }
            this.start = start;
        }

        public String getEnd() {
            return end;
        }

        public void setEnd(String end) {
            if (timestamp != null) {
                throw new IllegalArgumentException("Param [" + END.getPreferredName() + "] is incompatible with ["
                        + TIMESTAMP.getPreferredName() + "].");
            }
            this.end = end;
        }

        public PageParams getPageParams() {
            return pageParams;
        }

        public void setPageParams(PageParams pageParams) {
            if (timestamp != null) {
                throw new IllegalArgumentException("Param [" + PageParams.FROM.getPreferredName() 
                        + ", " + PageParams.SIZE.getPreferredName() + "] is incompatible with [" + TIMESTAMP.getPreferredName() + "].");
            }
            this.pageParams = ExceptionsHelper.requireNonNull(pageParams, PageParams.PAGE.getPreferredName());
        }

        public double getAnomalyScore() {
            return anomalyScore;
        }

        public void setAnomalyScore(double anomalyScore) {
            if (timestamp != null) {
                throw new IllegalArgumentException("Param [" + ANOMALY_SCORE.getPreferredName() + "] is incompatible with ["
                        + TIMESTAMP.getPreferredName() + "].");
            }
            this.anomalyScore = anomalyScore;
        }

        public double getMaxNormalizedProbability() {
            return maxNormalizedProbability;
        }

        public void setMaxNormalizedProbability(double maxNormalizedProbability) {
            if (timestamp != null) {
                throw new IllegalArgumentException("Param [" + MAX_NORMALIZED_PROBABILITY.getPreferredName() + "] is incompatible with ["
                        + TIMESTAMP.getPreferredName() + "].");
            }
            this.maxNormalizedProbability = maxNormalizedProbability;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobId = in.readString();
            timestamp = in.readOptionalString();
            expand = in.readBoolean();
            includeInterim = in.readBoolean();
            partitionValue = in.readOptionalString();
            start = in.readOptionalString();
            end = in.readOptionalString();
            anomalyScore = in.readOptionalDouble();
            maxNormalizedProbability = in.readOptionalDouble();
            pageParams = in.readOptionalWriteable(PageParams::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(jobId);
            out.writeOptionalString(timestamp);
            out.writeBoolean(expand);
            out.writeBoolean(includeInterim);
            out.writeOptionalString(partitionValue);
            out.writeOptionalString(start);
            out.writeOptionalString(end);
            out.writeOptionalDouble(anomalyScore);
            out.writeOptionalDouble(maxNormalizedProbability);
            out.writeOptionalWriteable(pageParams);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Job.ID.getPreferredName(), jobId);
            if (timestamp != null) {
                builder.field(Bucket.TIMESTAMP.getPreferredName(), timestamp);
            }
            builder.field(EXPAND.getPreferredName(), expand);
            builder.field(INCLUDE_INTERIM.getPreferredName(), includeInterim);
            if (partitionValue != null) {
                builder.field(PARTITION_VALUE.getPreferredName(), partitionValue);
            }
            if (start != null) {
                builder.field(START.getPreferredName(), start);
            }
            if (end != null) {
                builder.field(END.getPreferredName(), end);
            }
            if (pageParams != null) {
                builder.field(PageParams.PAGE.getPreferredName(), pageParams);
            }
            if (anomalyScore != null) {
                builder.field(ANOMALY_SCORE.getPreferredName(), anomalyScore);
            }
            if (maxNormalizedProbability != null) {
                builder.field(MAX_NORMALIZED_PROBABILITY.getPreferredName(), maxNormalizedProbability);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, timestamp, partitionValue, expand, includeInterim,
                    anomalyScore, maxNormalizedProbability, pageParams, start, end);
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
            return Objects.equals(jobId, other.jobId) &&
                    Objects.equals(timestamp, other.timestamp) &&
                    Objects.equals(partitionValue, other.partitionValue) &&
                    Objects.equals(expand, other.expand) &&
                    Objects.equals(includeInterim, other.includeInterim) &&
                    Objects.equals(anomalyScore, other.anomalyScore) &&
                    Objects.equals(maxNormalizedProbability, other.maxNormalizedProbability) &&
                    Objects.equals(pageParams, other.pageParams) &&
                    Objects.equals(start, other.start) &&
                    Objects.equals(end, other.end);
        }
    }

    static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        RequestBuilder(ElasticsearchClient client) {
            super(client, INSTANCE, new Request());
        }
    }

    public static class Response extends ActionResponse implements ToXContentObject {

        private QueryPage<Bucket> buckets;

        Response() {
        }

        Response(QueryPage<Bucket> buckets) {
            this.buckets = buckets;
        }

        public QueryPage<Bucket> getBuckets() {
            return buckets;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            buckets = new QueryPage<>(in, Bucket::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            buckets.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            buckets.doXContentBody(builder, params);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(buckets);
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
            return Objects.equals(buckets, other.buckets);
        }

        @SuppressWarnings("deprecation")
        @Override
        public final String toString() {
            try {
                XContentBuilder builder = XContentFactory.jsonBuilder();
                builder.prettyPrint();
                toXContent(builder, EMPTY_PARAMS);
                return builder.string();
            } catch (Exception e) {
                // So we have a stack trace logged somewhere
                return "{ \"error\" : \"" + org.elasticsearch.ExceptionsHelper.detailedMessage(e) + "\"}";
            }
        }
    }

    public static class TransportAction extends HandledTransportAction<Request, Response> {

        private final JobProvider jobProvider;

        @Inject
        public TransportAction(Settings settings, ThreadPool threadPool, TransportService transportService,
                ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                JobProvider jobProvider) {
            super(settings, NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, Request::new);
            this.jobProvider = jobProvider;
        }

        @Override
        protected void doExecute(Request request, ActionListener<Response> listener) {
            BucketsQueryBuilder query =
                    new BucketsQueryBuilder().expand(request.expand)
                            .includeInterim(request.includeInterim)
                            .start(request.start)
                            .end(request.end)
                            .anomalyScoreThreshold(request.anomalyScore)
                            .normalizedProbabilityThreshold(request.maxNormalizedProbability)
                            .partitionValue(request.partitionValue);

            if (request.pageParams != null) {
                query.from(request.pageParams.getFrom())
                        .size(request.pageParams.getSize());
            }
            if (request.timestamp != null) {
                query.timestamp(request.timestamp);
            } else {
                query.start(request.start);
                query.end(request.end);
            }
            jobProvider.buckets(request.jobId, query.build(), q -> listener.onResponse(new Response(q)), listener::onFailure);
        }
    }

}
