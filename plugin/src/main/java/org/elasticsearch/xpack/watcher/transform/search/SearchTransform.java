/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.transform.search;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.watcher.support.WatcherDateTimeUtils;
import org.elasticsearch.xpack.watcher.support.search.WatcherSearchTemplateRequest;
import org.elasticsearch.xpack.watcher.transform.Transform;
import org.elasticsearch.xpack.watcher.watch.Payload;
import org.joda.time.DateTimeZone;

import java.io.IOException;

import static org.elasticsearch.common.unit.TimeValue.timeValueMillis;

public class SearchTransform implements Transform {

    public static final String TYPE = "search";

    private final WatcherSearchTemplateRequest request;
    @Nullable private final TimeValue timeout;
    @Nullable private final DateTimeZone dynamicNameTimeZone;

    public SearchTransform(WatcherSearchTemplateRequest request, @Nullable TimeValue timeout, @Nullable DateTimeZone dynamicNameTimeZone) {
        this.request = request;
        this.timeout = timeout;
        this.dynamicNameTimeZone = dynamicNameTimeZone;
    }

    @Override
    public String type() {
        return TYPE;
    }

    public WatcherSearchTemplateRequest getRequest() {
        return request;
    }

    public TimeValue getTimeout() {
        return timeout;
    }

    public DateTimeZone getDynamicNameTimeZone() {
        return dynamicNameTimeZone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        SearchTransform that = (SearchTransform) o;

        if (request != null ? !request.equals(that.request) : that.request != null) return false;
        if (timeout != null ? !timeout.equals(that.timeout) : that.timeout != null) return false;
        return !(dynamicNameTimeZone != null ? !dynamicNameTimeZone.equals(that.dynamicNameTimeZone) : that.dynamicNameTimeZone != null);
    }

    @Override
    public int hashCode() {
        int result = request != null ? request.hashCode() : 0;
        result = 31 * result + (timeout != null ? timeout.hashCode() : 0);
        result = 31 * result + (dynamicNameTimeZone != null ? dynamicNameTimeZone.hashCode() : 0);
        return result;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (request != null) {
            builder.field(Field.REQUEST.getPreferredName(), request);
        }
        if (timeout != null) {
            builder.timeValueField(Field.TIMEOUT.getPreferredName(), Field.TIMEOUT_HUMAN.getPreferredName(), timeout);
        }
        if (dynamicNameTimeZone != null) {
            builder.field(Field.DYNAMIC_NAME_TIMEZONE.getPreferredName(), dynamicNameTimeZone);
        }
        builder.endObject();
        return builder;
    }

    public static SearchTransform parse(String watchId, XContentParser parser) throws IOException {
        WatcherSearchTemplateRequest request = null;
        TimeValue timeout = null;
        DateTimeZone dynamicNameTimeZone = null;

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (Field.REQUEST.match(currentFieldName)) {
                try {
                    request = WatcherSearchTemplateRequest.fromXContent(parser, ExecutableSearchTransform.DEFAULT_SEARCH_TYPE);
                } catch (ElasticsearchParseException srpe) {
                    throw new ElasticsearchParseException("could not parse [{}] transform for watch [{}]. failed to parse [{}]", srpe,
                            TYPE, watchId, currentFieldName);
                }
            } else if (Field.TIMEOUT.match(currentFieldName)) {
                timeout = timeValueMillis(parser.longValue());
            } else if (Field.TIMEOUT_HUMAN.match(currentFieldName)) {
                // Parser for human specified timeouts and 2.x compatibility
                timeout = WatcherDateTimeUtils.parseTimeValue(parser, Field.TIMEOUT_HUMAN.toString());
            } else if (Field.DYNAMIC_NAME_TIMEZONE.match(currentFieldName)) {
                if (token == XContentParser.Token.VALUE_STRING) {
                    dynamicNameTimeZone = DateTimeZone.forID(parser.text());
                } else {
                    throw new ElasticsearchParseException("could not parse [{}] transform for watch [{}]. failed to parse [{}]. must be a" +
                            " string value (e.g. 'UTC' or '+01:00').", TYPE, watchId, currentFieldName);
                }
            } else {
                throw new ElasticsearchParseException("could not parse [{}] transform for watch [{}]. unexpected field [{}]", TYPE,
                        watchId, currentFieldName);
            }
        }

        if (request == null) {
            throw new ElasticsearchParseException("could not parse [{}] transform for watch [{}]. missing required [{}] field", TYPE,
                    watchId, Field.REQUEST.getPreferredName());
        }
        return new SearchTransform(request, timeout, dynamicNameTimeZone);
    }

    public static Builder builder(WatcherSearchTemplateRequest request) {
        return new Builder(request);
    }

    public static class Result extends Transform.Result {

        @Nullable private final WatcherSearchTemplateRequest request;

        public Result(WatcherSearchTemplateRequest request, Payload payload) {
            super(TYPE, payload);
            this.request = request;
        }

        public Result(WatcherSearchTemplateRequest request, Exception e) {
            super(TYPE, e);
            this.request = request;
        }

        public WatcherSearchTemplateRequest executedRequest() {
            return request;
        }

        @Override
        protected XContentBuilder typeXContent(XContentBuilder builder, Params params) throws IOException {
            if (request != null) {
                builder.startObject(type);
                builder.field(Field.REQUEST.getPreferredName(), request);
                builder.endObject();
            }
            return builder;
        }
    }

    public static class Builder implements Transform.Builder<SearchTransform> {

        private final WatcherSearchTemplateRequest request;
        private TimeValue timeout;
        private DateTimeZone dynamicNameTimeZone;

        public Builder(WatcherSearchTemplateRequest request) {
            this.request = request;
        }

        public Builder timeout(TimeValue readTimeout) {
            this.timeout = readTimeout;
            return this;
        }

        public Builder dynamicNameTimeZone(DateTimeZone dynamicNameTimeZone) {
            this.dynamicNameTimeZone = dynamicNameTimeZone;
            return this;
        }

        @Override
        public SearchTransform build() {
            return new SearchTransform(request, timeout, dynamicNameTimeZone);
        }
    }

    public interface Field {
        ParseField REQUEST = new ParseField("request");
        ParseField TIMEOUT = new ParseField("timeout_in_millis");
        ParseField TIMEOUT_HUMAN = new ParseField("timeout");
        ParseField DYNAMIC_NAME_TIMEZONE = new ParseField("dynamic_name_timezone");
    }
}
