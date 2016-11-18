/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.action;

import org.elasticsearch.ElasticsearchException;
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
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.StatusToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.prelert.PrelertPlugin;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.manager.AutodetectProcessManager;
import org.elasticsearch.xpack.prelert.job.process.autodetect.params.DataLoadParams;
import org.elasticsearch.xpack.prelert.job.process.autodetect.params.TimeRange;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Objects;

public class PostDataAction extends Action<PostDataAction.Request, PostDataAction.Response, PostDataAction.RequestBuilder> {

    public static final PostDataAction INSTANCE = new PostDataAction();
    public static final String NAME = "cluster:admin/prelert/data/post";

    private PostDataAction() {
        super(NAME);
    }

    @Override
    public RequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new RequestBuilder(client, this);
    }

    @Override
    public Response newResponse() {
        return null;
    }

    static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        RequestBuilder(ElasticsearchClient client, PostDataAction action) {
            super(client, action, new Request());
        }
    }

    public static class Response extends ActionResponse implements StatusToXContent {

        private DataCounts dataCounts;

        Response(String jobId) {
            dataCounts = new DataCounts(jobId);
        }

        public Response(DataCounts counts) {
            this.dataCounts = counts;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            dataCounts = new DataCounts(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            dataCounts.writeTo(out);
        }

        @Override
        public RestStatus status() {
            return RestStatus.ACCEPTED;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return dataCounts.doXContentBody(builder, params);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(dataCounts);
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

            return Objects.equals(dataCounts, other.dataCounts);

        }
    }

    public static class Request extends ActionRequest {

        public static final ParseField IGNORE_DOWNTIME = new ParseField("ignoreDowntime");
        public static final ParseField RESET_START = new ParseField("resetStart");
        public static final ParseField RESET_END = new ParseField("resetEnd");

        private String jobId;
        private boolean ignoreDowntime = false;
        private String resetStart;
        private String resetEnd;
        private BytesReference content;

        Request() {
        }

        public Request(String jobId) {
            ExceptionsHelper.requireNonNull(jobId, "jobId");
            this.jobId = jobId;
        }

        public String getJobId() {
            return jobId;
        }

        public boolean isIgnoreDowntime() {
            return ignoreDowntime;
        }

        public void setIgnoreDowntime(boolean ignoreDowntime) {
            this.ignoreDowntime = ignoreDowntime;
        }

        public String getResetStart() {
            return resetStart;
        }

        public void setResetStart(String resetStart) {
            this.resetStart = resetStart;
        }

        public String getResetEnd() {
            return resetEnd;
        }

        public void setResetEnd(String resetEnd) {
            this.resetEnd = resetEnd;
        }

        public BytesReference getContent() { return content; }

        public void setContent(BytesReference content) {
            this.content = content;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobId = in.readString();
            ignoreDowntime = in.readBoolean();
            resetStart = in.readOptionalString();
            resetEnd = in.readOptionalString();
            content = in.readBytesReference();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(jobId);
            out.writeBoolean(ignoreDowntime);
            out.writeOptionalString(resetStart);
            out.writeOptionalString(resetEnd);
            out.writeBytesReference(content);
        }

        @Override
        public int hashCode() {
            // content stream not included
            return Objects.hash(jobId, ignoreDowntime, resetStart, resetEnd);
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

            // content stream not included
            return Objects.equals(jobId, other.jobId) &&
                    Objects.equals(ignoreDowntime, other.ignoreDowntime) &&
                    Objects.equals(resetStart, other.resetStart) &&
                    Objects.equals(resetEnd, other.resetEnd);
        }
    }


    public static class TransportAction extends HandledTransportAction<Request, Response> {

        private final AutodetectProcessManager processManager;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ThreadPool threadPool, ActionFilters actionFilters,
                IndexNameExpressionResolver indexNameExpressionResolver, AutodetectProcessManager processManager) {
            super(settings, PostDataAction.NAME, false, threadPool, transportService, actionFilters,
                    indexNameExpressionResolver, Request::new);
            this.processManager = processManager;
        }

        @Override
        protected final void doExecute(Request request, ActionListener<Response> listener) {

            TimeRange timeRange = TimeRange.builder().startTime(request.getResetStart()).endTime(request.getResetEnd()).build();
            DataLoadParams params = new DataLoadParams(timeRange, request.isIgnoreDowntime());

            // NORELEASE Make this all async so we don't need to pass off to another thread pool and block
            threadPool.executor(PrelertPlugin.THREAD_POOL_NAME).execute(() -> {
                try {
                    DataCounts dataCounts = processManager.processData(request.getJobId(), request.content.streamInput(), params);
                    listener.onResponse(new Response(dataCounts));
                } catch (IOException | ElasticsearchException e) {
                    listener.onFailure(e);
                }
            });
        }
    }
}
