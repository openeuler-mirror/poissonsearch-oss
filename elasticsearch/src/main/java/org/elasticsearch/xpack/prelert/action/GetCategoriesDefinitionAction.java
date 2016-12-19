/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.persistence.JobProvider;
import org.elasticsearch.xpack.prelert.job.persistence.QueryPage;
import org.elasticsearch.xpack.prelert.job.results.CategoryDefinition;
import org.elasticsearch.xpack.prelert.job.results.PageParams;
import org.elasticsearch.xpack.prelert.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class GetCategoriesDefinitionAction extends
Action<GetCategoriesDefinitionAction.Request, GetCategoriesDefinitionAction.Response, GetCategoriesDefinitionAction.RequestBuilder> {

    public static final GetCategoriesDefinitionAction INSTANCE = new GetCategoriesDefinitionAction();
    private static final String NAME = "cluster:admin/prelert/categorydefinitions/get";

    private GetCategoriesDefinitionAction() {
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

    public static class Request extends ActionRequest implements ToXContent {

        public static final ParseField CATEGORY_ID = new ParseField("category_id");
        public static final ParseField FROM = new ParseField("from");
        public static final ParseField SIZE = new ParseField("size");

        private static final ObjectParser<Request, ParseFieldMatcherSupplier> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString((request, jobId) -> request.jobId = jobId, Job.ID);
            PARSER.declareString(Request::setCategoryId, CATEGORY_ID);
            PARSER.declareObject(Request::setPageParams, PageParams.PARSER, PageParams.PAGE);
        }

        public static Request parseRequest(String jobId, XContentParser parser, ParseFieldMatcherSupplier parseFieldMatcherSupplier) {
            Request request = PARSER.apply(parser, parseFieldMatcherSupplier);
            if (jobId != null) {
                request.jobId = jobId;
            }
            return request;
        }

        private String jobId;
        private String categoryId;
        private PageParams pageParams;

        public Request(String jobId) {
            this.jobId = ExceptionsHelper.requireNonNull(jobId, Job.ID.getPreferredName());
        }

        Request() {
        }

        public String getCategoryId() {
            return categoryId;
        }

        public void setCategoryId(String categoryId) {
            if (pageParams != null) {
                throw new IllegalArgumentException("Param [" + CATEGORY_ID.getPreferredName() + "] is incompatible with ["
                        + PageParams.FROM.getPreferredName() + ", " + PageParams.SIZE.getPreferredName() + "].");
            }
            this.categoryId = ExceptionsHelper.requireNonNull(categoryId, CATEGORY_ID.getPreferredName());
        }

        public PageParams getPageParams() {
            return pageParams;
        }

        public void setPageParams(PageParams pageParams) {
            if (categoryId != null) {
                throw new IllegalArgumentException("Param [" + PageParams.FROM.getPreferredName() + ", "
                        + PageParams.SIZE.getPreferredName() + "] is incompatible with [" + CATEGORY_ID.getPreferredName() + "].");
            }
            this.pageParams = pageParams;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;
            if (pageParams == null && categoryId == null) {
                validationException = addValidationError("Both [" + CATEGORY_ID.getPreferredName() + "] and ["
                        + PageParams.FROM.getPreferredName() + ", " + PageParams.SIZE.getPreferredName() + "] "
                        + "cannot be null" , validationException);
            }
            return validationException;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            jobId = in.readString();
            categoryId = in.readOptionalString();
            pageParams = in.readOptionalWriteable(PageParams::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(jobId);
            out.writeOptionalString(categoryId);
            out.writeOptionalWriteable(pageParams);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Job.ID.getPreferredName(), jobId);
            if (categoryId != null) {
                builder.field(CATEGORY_ID.getPreferredName(), categoryId);
            }
            if (pageParams != null) {
                builder.field(PageParams.PAGE.getPreferredName(), pageParams);
            }
            builder.endObject();
            return builder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Request request = (Request) o;
            return Objects.equals(jobId, request.jobId)
                    && Objects.equals(categoryId, request.categoryId)
                    && Objects.equals(pageParams, request.pageParams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(jobId, categoryId, pageParams);
        }
    }

    public static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client, GetCategoriesDefinitionAction action) {
            super(client, action, new Request());
        }
    }

    public static class Response extends ActionResponse implements ToXContent {

        private QueryPage<CategoryDefinition> result;

        public Response(QueryPage<CategoryDefinition> result) {
            this.result = result;
        }

        Response() {
        }

        public QueryPage<CategoryDefinition> getResult() {
            return result;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            result = new QueryPage<>(in, CategoryDefinition::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            result.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            result.doXContentBody(builder, params);
            return builder;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Response response = (Response) o;
            return Objects.equals(result, response.result);
        }

        @Override
        public int hashCode() {
            return Objects.hash(result);
        }
    }

    public static class TransportAction extends HandledTransportAction<Request, Response> {

        private final JobProvider jobProvider;

        @Inject
        public TransportAction(Settings settings, ThreadPool threadPool, TransportService transportService, ActionFilters actionFilters,
                IndexNameExpressionResolver indexNameExpressionResolver, JobProvider jobProvider) {
            super(settings, NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, Request::new);
            this.jobProvider = jobProvider;
        }

        @Override
        protected void doExecute(Request request, ActionListener<Response> listener) {
            QueryPage<CategoryDefinition> result;
            if (request.categoryId != null ) {
                result = jobProvider.categoryDefinition(request.jobId, request.categoryId);
            } else if (request.pageParams != null) {
                result = jobProvider.categoryDefinitions(request.jobId, request.pageParams.getFrom(), request.pageParams.getSize());
            } else {
                throw new IllegalStateException("Both categoryId and pageParams are null");
            }
            listener.onResponse(new Response(result));
        }
    }
}
