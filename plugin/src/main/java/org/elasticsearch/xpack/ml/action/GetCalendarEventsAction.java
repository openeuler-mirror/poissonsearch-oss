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
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.ml.action.util.PageParams;
import org.elasticsearch.xpack.ml.action.util.QueryPage;
import org.elasticsearch.xpack.ml.calendars.Calendar;
import org.elasticsearch.xpack.ml.calendars.SpecialEvent;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.ml.job.persistence.SpecialEventsQueryBuilder;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Collections;
import java.util.Objects;

public class GetCalendarEventsAction extends Action<GetCalendarEventsAction.Request, GetCalendarEventsAction.Response,
        GetCalendarEventsAction.RequestBuilder> {
    public static final GetCalendarEventsAction INSTANCE = new GetCalendarEventsAction();
    public static final String NAME = "cluster:monitor/xpack/ml/calendars/events/get";

    private GetCalendarEventsAction() {
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

        public static final ParseField AFTER = new ParseField("after");
        public static final ParseField BEFORE = new ParseField("before");

        private static final ObjectParser<Request, Void> PARSER = new ObjectParser<>(NAME, Request::new);

        static {
            PARSER.declareString(Request::setCalendarId, Calendar.ID);
            PARSER.declareString(Request::setAfter, AFTER);
            PARSER.declareString(Request::setBefore, BEFORE);
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
        private String after;
        private String before;
        private PageParams pageParams = PageParams.defaultParams();

        Request() {
        }

        public Request(String calendarId) {
            setCalendarId(calendarId);
        }

        public String getCalendarId() {
            return calendarId;
        }

        private void setCalendarId(String calendarId) {
            this.calendarId = ExceptionsHelper.requireNonNull(calendarId, Calendar.ID.getPreferredName());
        }

        public String getAfter() {
            return after;
        }
        public void setAfter(String after) {
            this.after = after;
        }

        public String getBefore() {
            return before;
        }

        public void setBefore(String before) {
            this.before = before;
        }

        public PageParams getPageParams() {
            return pageParams;
        }

        public void setPageParams(PageParams pageParams) {
            this.pageParams = Objects.requireNonNull(pageParams);
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            calendarId = in.readString();
            after = in.readOptionalString();
            before = in.readOptionalString();
            pageParams = new PageParams(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(calendarId);
            out.writeOptionalString(after);
            out.writeOptionalString(before);
            pageParams.writeTo(out);
        }

        @Override
        public int hashCode() {
            return Objects.hash(calendarId, after, before, pageParams);
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
            return Objects.equals(calendarId, other.calendarId) && Objects.equals(after, other.after)
                    && Objects.equals(before, other.before) && Objects.equals(pageParams, other.pageParams);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Calendar.ID.getPreferredName(), calendarId);
            if (after != null) {
                builder.field(AFTER.getPreferredName(), after);
            }
            if (before != null) {
                builder.field(BEFORE.getPreferredName(), before);
            }
            builder.field(PageParams.PAGE.getPreferredName(), pageParams);
            builder.endObject();
            return builder;
        }
    }

    public static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client) {
            super(client, INSTANCE, new Request());
        }
    }

    public static class Response extends ActionResponse implements ToXContentObject {

        private QueryPage<SpecialEvent> specialEvents;

        Response() {
        }

        public Response(QueryPage<SpecialEvent> specialEvents) {
            this.specialEvents = specialEvents;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            specialEvents = new QueryPage<>(in, SpecialEvent::new);

        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            specialEvents.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return specialEvents.toXContent(builder, params);
        }

        @Override
        public int hashCode() {
            return Objects.hash(specialEvents);
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
            return Objects.equals(specialEvents, other.specialEvents);
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
            ActionListener<Boolean> calendarExistsListener = ActionListener.wrap(
                    r -> {
                        SpecialEventsQueryBuilder query = new SpecialEventsQueryBuilder()
                                .calendarIds(Collections.singletonList(request.getCalendarId()))
                                .after(request.getAfter())
                                .before(request.getBefore())
                                .from(request.getPageParams().getFrom())
                                .size(request.getPageParams().getSize());

                        jobProvider.specialEvents(query, ActionListener.wrap(
                                events -> {
                                    listener.onResponse(new Response(events));
                                },
                                listener::onFailure
                        ));
                    },
                    listener::onFailure);

            checkCalendarExists(request.getCalendarId(), calendarExistsListener);
        }

        private void checkCalendarExists(String calendarId, ActionListener<Boolean> listener) {
            jobProvider.calendar(calendarId, ActionListener.wrap(
                    c -> listener.onResponse(true),
                    listener::onFailure
            ));
        }
    }
}
