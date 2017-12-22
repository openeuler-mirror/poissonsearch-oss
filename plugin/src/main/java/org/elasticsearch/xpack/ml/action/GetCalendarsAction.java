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
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.StatusToXContentObject;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.action.util.PageParams;
import org.elasticsearch.xpack.ml.action.util.QueryPage;
import org.elasticsearch.xpack.ml.calendars.Calendar;
import org.elasticsearch.xpack.ml.job.persistence.CalendarQueryBuilder;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class GetCalendarsAction extends Action<GetCalendarsAction.Request, GetCalendarsAction.Response, GetCalendarsAction.RequestBuilder> {

    public static final GetCalendarsAction INSTANCE = new GetCalendarsAction();
    public static final String NAME = "cluster:monitor/xpack/ml/calendars/get";

    private GetCalendarsAction() {
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

    public static class Request extends ActionRequest implements ToXContentObject {

        public static final String ALL = "_all";

        private static final ObjectParser<Request, Void> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString(Request::setCalendarId, Calendar.ID);
            PARSER.declareObject(Request::setPageParams, PageParams.PARSER, PageParams.PAGE);
        }

        public static Request parseRequest(String calendarId, XContentParser parser) {
            Request request = PARSER.apply(parser, null);
            if (calendarId != null) {
                request.setCalendarId(calendarId);
            }
            return request;
        }

        private String calendarId;
        private PageParams pageParams;

        public Request() {
        }

        public void setCalendarId(String calendarId) {
            this.calendarId = calendarId;
        }

        public String getCalendarId() {
            return calendarId;
        }

        public PageParams getPageParams() {
            return pageParams;
        }

        public void setPageParams(PageParams pageParams) {
            this.pageParams = pageParams;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;

            if (calendarId != null && pageParams != null) {
                validationException = addValidationError("Params [" + PageParams.FROM.getPreferredName()
                                + ", " + PageParams.SIZE.getPreferredName() + "] are incompatible with ["
                                + Calendar.ID.getPreferredName() + "].",
                        validationException);
            }
            return validationException;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            calendarId = in.readOptionalString();
            pageParams = in.readOptionalWriteable(PageParams::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeOptionalString(calendarId);
            out.writeOptionalWriteable(pageParams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(calendarId, pageParams);
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
            return Objects.equals(calendarId, other.calendarId) && Objects.equals(pageParams, other.pageParams);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (calendarId != null) {
                builder.field(Calendar.ID.getPreferredName(), calendarId);
            }
            if (pageParams != null) {
                builder.field(PageParams.PAGE.getPreferredName(), pageParams);
            }
            builder.endObject();
            return builder;
        }
    }

    public static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client) {
            super(client, INSTANCE, new Request());
        }
    }

    public static class Response extends ActionResponse implements StatusToXContentObject {

        private QueryPage<Calendar> calendars;

        public Response(QueryPage<Calendar> calendars) {
            this.calendars = calendars;
        }

        Response() {
        }

        public QueryPage<Calendar> getCalendars() {
            return calendars;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            calendars = new QueryPage<>(in, Calendar::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            calendars.writeTo(out);
        }

        @Override
        public RestStatus status() {
            return RestStatus.OK;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            calendars.doXContentBody(builder, params);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(calendars);
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
            return Objects.equals(calendars, other.calendars);
        }

        @Override
        public final String toString() {
            return Strings.toString(this);
        }
    }

    public static class TransportAction extends HandledTransportAction<Request, Response> {

        private final JobProvider jobProvider;

        @Inject
        public TransportAction(Settings settings, ThreadPool threadPool,
                               TransportService transportService, ActionFilters actionFilters,
                               IndexNameExpressionResolver indexNameExpressionResolver,
                               JobProvider jobProvider) {
            super(settings, NAME, threadPool, transportService, actionFilters,
                    indexNameExpressionResolver, Request::new);
            this.jobProvider = jobProvider;
        }

        @Override
        protected void doExecute(Request request, ActionListener<Response> listener) {
            final String calendarId = request.getCalendarId();
            if (request.getCalendarId() != null && Request.ALL.equals(request.getCalendarId()) == false) {
                getCalendar(calendarId, listener);
            } else {
                PageParams pageParams = request.getPageParams();
                if (pageParams == null) {
                    pageParams = PageParams.defaultParams();
                }
                getCalendars(pageParams, listener);
            }
        }

        private void getCalendar(String calendarId, ActionListener<Response> listener) {

            jobProvider.calendar(calendarId, ActionListener.wrap(
                    calendar -> {
                        QueryPage<Calendar> page = new QueryPage<>(Collections.singletonList(calendar), 1, Calendar.RESULTS_FIELD);
                        listener.onResponse(new Response(page));
                    },
                    listener::onFailure
            ));
        }

        private void getCalendars(PageParams pageParams, ActionListener<Response> listener) {
            CalendarQueryBuilder query = new CalendarQueryBuilder().pageParams(pageParams).sort(true);
            jobProvider.calendars(query, ActionListener.wrap(
                    calendars -> {
                        listener.onResponse(new Response(calendars));
                    },
                    listener::onFailure
            ));
        }
    }
}
