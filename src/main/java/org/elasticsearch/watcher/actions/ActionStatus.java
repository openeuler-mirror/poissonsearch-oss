/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.watcher.WatcherException;

import java.io.IOException;
import java.util.Locale;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.elasticsearch.watcher.support.WatcherDateTimeUtils.dateTimeFormatter;

/**
 *
 */
public class ActionStatus implements ToXContent {

    private AckStatus ackStatus;
    private @Nullable Execution lastExecution;
    private @Nullable Execution lastSuccessfulExecution;
    private @Nullable Throttle lastThrottle;

    public ActionStatus(DateTime now) {
        this(new AckStatus(now, AckStatus.State.AWAITS_SUCCESSFUL_EXECUTION), null, null, null);
    }

    public ActionStatus(AckStatus ackStatus, @Nullable Execution lastExecution, @Nullable Execution lastSuccessfulExecution, @Nullable Throttle lastThrottle) {
        this.ackStatus = ackStatus;
        this.lastExecution = lastExecution;
        this.lastSuccessfulExecution = lastSuccessfulExecution;
        this.lastThrottle = lastThrottle;
    }

    public AckStatus ackStatus() {
        return ackStatus;
    }

    public Execution lastExecution() {
        return lastExecution;
    }

    public Execution lastSuccessfulExecution() {
        return lastSuccessfulExecution;
    }

    public Throttle lastThrottle() {
        return lastThrottle;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ActionStatus that = (ActionStatus) o;

        if (!ackStatus.equals(that.ackStatus)) return false;
        if (lastExecution != null ? !lastExecution.equals(that.lastExecution) : that.lastExecution != null)
            return false;
        if (lastSuccessfulExecution != null ? !lastSuccessfulExecution.equals(that.lastSuccessfulExecution) : that.lastSuccessfulExecution != null)
            return false;
        return !(lastThrottle != null ? !lastThrottle.equals(that.lastThrottle) : that.lastThrottle != null);

    }

    @Override
    public int hashCode() {
        int result = ackStatus.hashCode();
        result = 31 * result + (lastExecution != null ? lastExecution.hashCode() : 0);
        result = 31 * result + (lastSuccessfulExecution != null ? lastSuccessfulExecution.hashCode() : 0);
        result = 31 * result + (lastThrottle != null ? lastThrottle.hashCode() : 0);
        return result;
    }

    public void update(DateTime timestamp, Action.Result result) {
        switch (result.status()) {

            case FAILURE:
                String reason = result instanceof Action.Result.Failure ? ((Action.Result.Failure) result).reason() : "";
                lastExecution = Execution.failure(timestamp, reason);
                return;

            case THROTTLED:
                reason = result instanceof Action.Result.Throttled ? ((Action.Result.Throttled) result).reason() : "";
                lastThrottle = new Throttle(timestamp, reason);
                return;

            case SUCCESS:
            case SIMULATED:
                lastExecution = Execution.successful(timestamp);
                lastSuccessfulExecution = lastExecution;
                if (ackStatus.state == AckStatus.State.AWAITS_SUCCESSFUL_EXECUTION) {
                    ackStatus = new AckStatus(timestamp, AckStatus.State.ACKABLE);
                }
        }
    }

    public boolean onAck(DateTime timestamp) {
        if (ackStatus.state == AckStatus.State.ACKABLE) {
            ackStatus = new AckStatus(timestamp, AckStatus.State.ACKED);
            return true;
        }
        return false;
    }

    public boolean resetAckStatus(DateTime timestamp) {
        if (ackStatus.state != AckStatus.State.AWAITS_SUCCESSFUL_EXECUTION) {
            ackStatus = new AckStatus(timestamp, AckStatus.State.AWAITS_SUCCESSFUL_EXECUTION);
            return true;
        }
        return false;
    }

    public static void writeTo(ActionStatus status, StreamOutput out) throws IOException {
        AckStatus.writeTo(status.ackStatus, out);
        out.writeBoolean(status.lastExecution != null);
        if (status.lastExecution != null) {
            Execution.writeTo(status.lastExecution, out);
        }
        out.writeBoolean(status.lastSuccessfulExecution != null);
        if (status.lastSuccessfulExecution != null) {
            Execution.writeTo(status.lastSuccessfulExecution, out);
        }
        out.writeBoolean(status.lastThrottle != null);
        if (status.lastThrottle != null) {
            Throttle.writeTo(status.lastThrottle, out);
        }
    }

    public static ActionStatus readFrom(StreamInput in) throws IOException {
        AckStatus ackStatus = AckStatus.readFrom(in);
        Execution lastExecution = in.readBoolean() ? Execution.readFrom(in) : null;
        Execution lastSuccessfulExecution = in.readBoolean() ? Execution.readFrom(in) : null;
        Throttle lastThrottle = in.readBoolean() ? Throttle.readFrom(in) : null;
        return new ActionStatus(ackStatus, lastExecution, lastSuccessfulExecution, lastThrottle);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(Field.ACK_STATUS.getPreferredName(), ackStatus, params);
        if (lastExecution != null) {
            builder.field(Field.LAST_EXECUTION.getPreferredName(), lastExecution, params);
        }
        if (lastSuccessfulExecution != null) {
            builder.field(Field.LAST_SUCCESSFUL_EXECUTION.getPreferredName(), lastSuccessfulExecution, params);
        }
        if (lastThrottle != null) {
            builder.field(Field.LAST_THROTTLE.getPreferredName(), lastThrottle, params);
        }
        return builder.endObject();
    }

    public static ActionStatus parse(String watchId, String actionId, XContentParser parser) throws IOException {
        AckStatus ackStatus = null;
        Execution lastExecution = null;
        Execution lastSuccessfulExecution = null;
        Throttle lastThrottle = null;

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (Field.ACK_STATUS.match(currentFieldName)) {
                ackStatus = AckStatus.parse(watchId, actionId, parser);
            } else if (Field.LAST_EXECUTION.match(currentFieldName)) {
                lastExecution = Execution.parse(watchId, actionId, parser);
            } else if (Field.LAST_SUCCESSFUL_EXECUTION.match(currentFieldName)) {
                lastSuccessfulExecution = Execution.parse(watchId, actionId, parser);
            } else if (Field.LAST_THROTTLE.match(currentFieldName)) {
                lastThrottle = Throttle.parse(watchId, actionId, parser);
            } else {
                throw new ParseException("could not parse action status for [{}/{}]. unexpected field [{}]", watchId, actionId, currentFieldName);
            }
        }
        if (ackStatus == null) {
            throw new ParseException("could not parse action status for [{}/{}]. missing required field [{}]", watchId, actionId, Field.ACK_STATUS.getPreferredName());
        }
        return new ActionStatus(ackStatus, lastExecution, lastSuccessfulExecution, lastThrottle);
    }

    public static class AckStatus implements ToXContent {

        public enum State {
            AWAITS_SUCCESSFUL_EXECUTION((byte) 1),
            ACKABLE((byte) 2),
            ACKED((byte) 3);

            private byte value;

            State(byte value) {
                this.value = value;
            }

            static State resolve(byte value) {
                switch (value) {
                    case 1 : return AWAITS_SUCCESSFUL_EXECUTION;
                    case 2 : return ACKABLE;
                    case 3 : return ACKED;
                    default:
                        throw new WatcherException("unknown action ack status state value [{}]", value);
                }
            }
        }

        private final DateTime timestamp;
        private final State state;

        public AckStatus(DateTime timestamp, State state) {
            this.timestamp = timestamp.toDateTime(UTC);
            this.state = state;
        }

        public DateTime timestamp() {
            return timestamp;
        }

        public State state() {
            return state;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            AckStatus ackStatus = (AckStatus) o;

            if (!timestamp.equals(ackStatus.timestamp)) return false;
            return state == ackStatus.state;
        }

        @Override
        public int hashCode() {
            int result = timestamp.hashCode();
            result = 31 * result + state.hashCode();
            return result;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.startObject()
                    .field(Field.TIMESTAMP.getPreferredName()).value(timestamp, dateTimeFormatter.printer())
                    .field(Field.ACK_STATUS_STATE.getPreferredName(), state.name().toLowerCase(Locale.ROOT))
                    .endObject();
        }

        public static AckStatus parse(String watchId, String actionId, XContentParser parser) throws IOException {
            DateTime timestamp = null;
            State state = null;

            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (Field.TIMESTAMP.match(currentFieldName)) {
                    timestamp = dateTimeFormatter.parser().parseDateTime(parser.text());
                } else if (Field.ACK_STATUS_STATE.match(currentFieldName)) {
                    state = State.valueOf(parser.text().toUpperCase(Locale.ROOT));
                } else {
                    throw new ParseException("could not parse action status for [{}/{}]. unexpected field [{}.{}]", watchId, actionId, Field.ACK_STATUS.getPreferredName(), currentFieldName);
                }
            }
            if (timestamp == null) {
                throw new ParseException("could not parse action status for [{}/{}]. missing required field [{}.{}]", watchId, actionId, Field.ACK_STATUS.getPreferredName(), Field.TIMESTAMP.getPreferredName());
            }
            if (state == null) {
                throw new ParseException("could not parse action status for [{}/{}]. missing required field [{}.{}]", watchId, actionId, Field.ACK_STATUS.getPreferredName(), Field.ACK_STATUS_STATE.getPreferredName());
            }
            return new AckStatus(timestamp, state);
        }

        static void writeTo(AckStatus status, StreamOutput out) throws IOException {
            out.writeLong(status.timestamp.getMillis());
            out.writeByte(status.state.value);
        }

        static AckStatus readFrom(StreamInput in) throws IOException {
            DateTime timestamp = new DateTime(in.readLong(), UTC);
            State state = State.resolve(in.readByte());
            return new AckStatus(timestamp, state);
        }
    }

    public static class Execution implements ToXContent {

        public static Execution successful(DateTime timestamp) {
            return new Execution(timestamp, true, null);
        }

        public static Execution failure(DateTime timestamp, String reason) {
            return new Execution(timestamp, false, reason);
        }

        private final DateTime timestamp;
        private final boolean successful;
        private final String reason;

        private Execution(DateTime timestamp, boolean successful, String reason) {
            this.timestamp = timestamp.toDateTime(UTC);
            this.successful = successful;
            this.reason = reason;
        }

        public DateTime timestamp() {
            return timestamp;
        }

        public boolean successful() {
            return successful;
        }

        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Execution execution = (Execution) o;

            if (successful != execution.successful) return false;
            if (!timestamp.equals(execution.timestamp)) return false;
            return !(reason != null ? !reason.equals(execution.reason) : execution.reason != null);

        }

        @Override
        public int hashCode() {
            int result = timestamp.hashCode();
            result = 31 * result + (successful ? 1 : 0);
            result = 31 * result + (reason != null ? reason.hashCode() : 0);
            return result;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Field.TIMESTAMP.getPreferredName()).value(timestamp, dateTimeFormatter.printer());
            builder.field(Field.EXECUTION_SUCCESSFUL.getPreferredName(), successful);
            if (reason != null) {
                builder.field(Field.REASON.getPreferredName(), reason);
            }
            return builder.endObject();
        }

        public static Execution parse(String watchId, String actionId, XContentParser parser) throws IOException {
            DateTime timestamp = null;
            Boolean successful = null;
            String reason = null;

            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (Field.TIMESTAMP.match(currentFieldName)) {
                    timestamp = dateTimeFormatter.parser().parseDateTime(parser.text());
                } else if (Field.EXECUTION_SUCCESSFUL.match(currentFieldName)) {
                    successful = parser.booleanValue();
                } else if (Field.REASON.match(currentFieldName)) {
                    reason = parser.text();
                } else {
                    throw new ParseException("could not parse action status for [{}/{}]. unexpected field [{}.{}]", watchId, actionId, Field.LAST_EXECUTION.getPreferredName(), currentFieldName);
                }
            }
            if (timestamp == null) {
                throw new ParseException("could not parse action status for [{}/{}]. missing required field [{}.{}]", watchId, actionId, Field.LAST_EXECUTION.getPreferredName(), Field.TIMESTAMP.getPreferredName());
            }
            if (successful == null) {
                throw new ParseException("could not parse action status for [{}/{}]. missing required field [{}.{}]", watchId, actionId, Field.LAST_EXECUTION.getPreferredName(), Field.EXECUTION_SUCCESSFUL.getPreferredName());
            }
            if (successful) {
                return successful(timestamp);
            }
            if (reason == null) {
                throw new ParseException("could not parse action status for [{}/{}]. missing required field for unsuccessful execution [{}.{}]", watchId, actionId, Field.LAST_EXECUTION.getPreferredName(), Field.REASON.getPreferredName());
            }
            return failure(timestamp, reason);
        }

        public static void writeTo(Execution execution, StreamOutput out) throws IOException {
            out.writeLong(execution.timestamp.getMillis());
            out.writeBoolean(execution.successful);
            if (!execution.successful) {
                out.writeString(execution.reason);
            }
        }

        public static Execution readFrom(StreamInput in) throws IOException {
            DateTime timestamp = new DateTime(in.readLong(), UTC);
            boolean successful = in.readBoolean();
            if (successful) {
                return successful(timestamp);
            }
            return failure(timestamp, in.readSharedString());
        }
    }

    public static class Throttle implements ToXContent {

        private final DateTime timestamp;
        private final String reason;

        public Throttle(DateTime timestamp, String reason) {
            this.timestamp = timestamp.toDateTime(UTC);
            this.reason = reason;
        }

        public DateTime timestamp() {
            return timestamp;
        }

        public String reason() {
            return reason;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Throttle throttle = (Throttle) o;

            if (!timestamp.equals(throttle.timestamp)) return false;
            return reason.equals(throttle.reason);
        }

        @Override
        public int hashCode() {
            int result = timestamp.hashCode();
            result = 31 * result + reason.hashCode();
            return result;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.startObject()
                    .field(Field.TIMESTAMP.getPreferredName()).value(timestamp, dateTimeFormatter.printer())
                    .field(Field.REASON.getPreferredName(), reason)
                    .endObject();
        }

        public static Throttle parse(String watchId, String actionId, XContentParser parser) throws IOException {
            DateTime timestamp = null;
            String reason = null;

            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (Field.TIMESTAMP.match(currentFieldName)) {
                    timestamp = dateTimeFormatter.parser().parseDateTime(parser.text());
                } else if (Field.REASON.match(currentFieldName)) {
                    reason = parser.text();
                } else {
                    throw new ParseException("could not parse action status for [{}/{}]. unexpected field [{}.{}]", watchId, actionId, Field.LAST_THROTTLE.getPreferredName(), currentFieldName);
                }
            }
            if (timestamp == null) {
                throw new ParseException("could not parse action status for [{}/{}]. missing required field [{}.{}]", watchId, actionId, Field.LAST_THROTTLE.getPreferredName(), Field.TIMESTAMP.getPreferredName());
            }
            if (reason == null) {
                throw new ParseException("could not parse action status for [{}/{}]. missing required field [{}.{}]", watchId, actionId, Field.LAST_THROTTLE.getPreferredName(), Field.REASON.getPreferredName());
            }
            return new Throttle(timestamp, reason);
        }

        static void writeTo(Throttle throttle, StreamOutput out) throws IOException {
            out.writeLong(throttle.timestamp.getMillis());
            out.writeString(throttle.reason);
        }

        static Throttle readFrom(StreamInput in) throws IOException {
            DateTime timestamp = new DateTime(in.readLong(), UTC);
            return new Throttle(timestamp, in.readString());
        }
    }

    static class ParseException extends WatcherException {

        public ParseException(String msg, Object... args) {
            super(msg, args);
        }

        public ParseException(String msg, Throwable cause, Object... args) {
            super(msg, cause, args);
        }
    }

    interface Field {
        ParseField ACK_STATUS = new ParseField("ack_status");
        ParseField ACK_STATUS_STATE = new ParseField("state");

        ParseField LAST_EXECUTION = new ParseField("last_execution");
        ParseField LAST_SUCCESSFUL_EXECUTION = new ParseField("last_successful_execution");
        ParseField EXECUTION_SUCCESSFUL = new ParseField("successful");

        ParseField LAST_THROTTLE = new ParseField("last_throttle");

        ParseField TIMESTAMP = new ParseField("timestamp");
        ParseField REASON = new ParseField("reason");
    }
}
