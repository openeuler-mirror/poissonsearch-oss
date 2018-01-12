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
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContentObject;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.ml.calendars.Calendar;
import org.elasticsearch.xpack.ml.job.messages.Messages;
import org.elasticsearch.xpack.ml.utils.ExceptionsHelper;

import java.io.IOException;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class PutCalendarAction extends Action<PutCalendarAction.Request, PutCalendarAction.Response, PutCalendarAction.RequestBuilder>  {
    public static final PutCalendarAction INSTANCE = new PutCalendarAction();
    public static final String NAME = "cluster:admin/xpack/ml/calendars/put";

    private PutCalendarAction() {
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

        public static Request parseRequest(String calendarId, XContentParser parser) {
            Calendar.Builder builder = Calendar.PARSER.apply(parser, null);
            if (builder.getId() == null) {
                builder.setId(calendarId);
            } else if (!Strings.isNullOrEmpty(calendarId) && !calendarId.equals(builder.getId())) {
                // If we have both URI and body filter ID, they must be identical
                throw new IllegalArgumentException(Messages.getMessage(Messages.INCONSISTENT_ID, Calendar.ID.getPreferredName(),
                        builder.getId(), calendarId));
            }
            return new Request(builder.build());
        }

        private Calendar calendar;

        Request() {

        }

        public Request(Calendar calendar) {
            this.calendar = ExceptionsHelper.requireNonNull(calendar, "calendar");
        }

        public Calendar getCalendar() {
            return calendar;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;
            if ("_all".equals(calendar.getId())) {
                validationException =
                        addValidationError("Cannot create a Calendar with the reserved name [_all]",
                                validationException);
            }
            return validationException;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            calendar = new Calendar(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            calendar.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            calendar.toXContent(builder, params);
            return builder;
        }

        @Override
        public int hashCode() {
            return Objects.hash(calendar);
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
            return Objects.equals(calendar, other.calendar);
        }
    }

    public static class RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder> {

        public RequestBuilder(ElasticsearchClient client) {
            super(client, INSTANCE, new Request());
        }
    }

    public static class Response extends AcknowledgedResponse implements ToXContentObject {

        private Calendar calendar;

        Response() {
        }

        public Response(Calendar calendar) {
            super(true);
            this.calendar = calendar;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            readAcknowledged(in);
            calendar = new Calendar(in);

        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            writeAcknowledged(out);
            calendar.writeTo(out);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return calendar.toXContent(builder, params);
        }

        @Override
        public int hashCode() {
            return Objects.hash(isAcknowledged(), calendar);
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
            return Objects.equals(isAcknowledged(), other.isAcknowledged()) && Objects.equals(calendar, other.calendar);
        }
    }
}
