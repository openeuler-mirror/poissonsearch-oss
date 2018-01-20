/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.ml.calendars.Calendar;
import org.elasticsearch.xpack.ml.calendars.ScheduledEvent;
import org.elasticsearch.xpack.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class PostCalendarEventsAction extends Action<PostCalendarEventsAction.Request, PostCalendarEventsAction.Response,
        PostCalendarEventsAction.RequestBuilder> {
    public static final PostCalendarEventsAction INSTANCE = new PostCalendarEventsAction();
    public static final String NAME = "cluster:admin/xpack/ml/calendars/events/post";

    public static final ParseField EVENTS = new ParseField("events");

    private PostCalendarEventsAction() {
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

    public static class Request extends ActionRequest {

        private static final ObjectParser<List<ScheduledEvent.Builder>, Void> PARSER = new ObjectParser<>(NAME, ArrayList::new);

        static {
            PARSER.declareObjectArray(List::addAll, (p, c) -> ScheduledEvent.PARSER.apply(p, null), ScheduledEvent.RESULTS_FIELD);
        }

        public static Request parseRequest(String calendarId, XContentParser parser) throws IOException {
            List<ScheduledEvent.Builder> events = PARSER.apply(parser, null);

            for (ScheduledEvent.Builder event : events) {
                if (event.getCalendarId() != null && event.getCalendarId().equals(calendarId) == false) {
                    throw ExceptionsHelper.badRequestException(Messages.getMessage(Messages.INCONSISTENT_ID,
                            Calendar.ID.getPreferredName(), event.getCalendarId(), calendarId));
                }
                // Set the calendar Id in case it is null
                event.calendarId(calendarId);
            }

            return new Request(calendarId, events.stream().map(ScheduledEvent.Builder::build).collect(Collectors.toList()));
        }

        private String calendarId;
        private List<ScheduledEvent> scheduledEvents;

        public Request() {
        }

        public Request(String calendarId, List<ScheduledEvent> scheduledEvents) {
            this.calendarId = ExceptionsHelper.requireNonNull(calendarId, Calendar.ID.getPreferredName());
            this.scheduledEvents = ExceptionsHelper.requireNonNull(scheduledEvents, EVENTS.getPreferredName());

            if (scheduledEvents.isEmpty()) {
                throw ExceptionsHelper.badRequestException("At least 1 event is required");
            }
        }

        public String getCalendarId() {
            return calendarId;
        }

        public List<ScheduledEvent> getScheduledEvents() {
            return scheduledEvents;
        }

        @Override
        public ActionRequestValidationException validate() {
            return null;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            calendarId = in.readString();
            scheduledEvents = in.readList(ScheduledEvent::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(calendarId);
            out.writeList(scheduledEvents);
        }

        @Override
        public int hashCode() {
            return Objects.hash(calendarId, scheduledEvents);
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
            return Objects.equals(calendarId, other.calendarId) && Objects.equals(scheduledEvents, other.scheduledEvents);
        }
    }

    public static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client) {
            super(client, INSTANCE, new Request());
        }
    }

    public static class Response extends AcknowledgedResponse implements ToXContentObject {

        private List<ScheduledEvent> scheduledEvents;

        public Response() {
        }

        public Response(List<ScheduledEvent> scheduledEvents) {
            super(true);
            this.scheduledEvents = scheduledEvents;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            readAcknowledged(in);
            in.readList(ScheduledEvent::new);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            writeAcknowledged(out);
            out.writeList(scheduledEvents);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(EVENTS.getPreferredName(), scheduledEvents);
            builder.endObject();
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(isAcknowledged(), scheduledEvents);
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
            return Objects.equals(isAcknowledged(), other.isAcknowledged()) && Objects.equals(scheduledEvents, other.scheduledEvents);
        }
    }
}
