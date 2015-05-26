/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.http;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.netty.handler.codec.http.HttpHeaders;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.watcher.WatcherException;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class HttpResponse implements ToXContent {

    private final int status;
    private final ImmutableMap<String, String[]> headers;
    private final BytesReference body;

    public HttpResponse(int status) {
        this(status, ImmutableMap.<String, String[]>of());
    }

    public HttpResponse(int status, ImmutableMap<String, String[]> headers) {
        this(status, (BytesReference) null, headers);
    }

    public HttpResponse(int status, @Nullable String body) {
        this(status, body != null ? new BytesArray(body) : null, ImmutableMap.<String, String[]>of());
    }

    public HttpResponse(int status, @Nullable String body, ImmutableMap<String, String[]> headers) {
        this(status, body != null ? new BytesArray(body) : null, headers);
    }

    public HttpResponse(int status, @Nullable byte[] body) {
        this(status, body != null ? new BytesArray(body) : null, ImmutableMap.<String, String[]>of());
    }

    public HttpResponse(int status, @Nullable byte[] body, ImmutableMap<String, String[]> headers) {
        this(status, body != null ? new BytesArray(body) : null, headers);
    }

    public HttpResponse(int status, @Nullable BytesReference body, ImmutableMap<String, String[]> headers) {
        this.status = status;
        this.body = body;
        this.headers = headers;
    }

    public int status() {
        return status;
    }

    public boolean hasContent() {
        return body != null;
    }

    public BytesReference body() {
        return body;
    }

    public ImmutableMap<String, String[]> headers() {
        return headers;
    }

    public String contentType() {
        String[] values = headers.get(HttpHeaders.Names.CONTENT_TYPE);
        if (values == null || values.length == 0) {
            return null;
        }
        return values[0];
    }

    public XContentType xContentType() {
        String[] values = headers.get(HttpHeaders.Names.CONTENT_TYPE);
        if (values == null || values.length == 0) {
            return null;
        }
        return XContentType.fromRestContentType(values[0]);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        HttpResponse that = (HttpResponse) o;

        if (status != that.status) return false;
        if (!headers.equals(that.headers)) return false;
        return !(body != null ? !body.equals(that.body) : that.body != null);
    }

    @Override
    public int hashCode() {
        int result = status;
        result = 31 * result + headers.hashCode();
        result = 31 * result + (body != null ? body.hashCode() : 0);
        return result;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder = builder.startObject().field(Field.STATUS.getPreferredName(), status);
        if (!headers.isEmpty()) {
            builder.startObject(Field.HEADERS.getPreferredName());
            for (Map.Entry<String, String[]> header : headers.entrySet()) {
                builder.array(header.getKey(), header.getValue());
            }
            builder.endObject();
        }
        if (hasContent()) {
            builder = builder.field(Field.BODY.getPreferredName(), body.toUtf8());
        }
        builder.endObject();
        return builder;
    }

    public static HttpResponse parse(XContentParser parser) throws IOException {
        assert parser.currentToken() == XContentParser.Token.START_OBJECT;

        int status = -1;
        String body = null;
        ImmutableMap.Builder<String, String[]> headers = ImmutableMap.builder();

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (currentFieldName == null) {
                throw new ParseException("could not parse http response. expected a field name but found [{}] instead", token);
            } else if (token == XContentParser.Token.VALUE_NUMBER) {
                if (Field.STATUS.match(currentFieldName)) {
                    status = parser.intValue();
                } else {
                    throw new ParseException("could not parse http response. unknown numeric field [{}]", currentFieldName);
                }
            } else if (token == XContentParser.Token.VALUE_STRING) {
                if (Field.BODY.match(currentFieldName)) {
                    body = parser.text();
                } else {
                    throw new ParseException("could not parse http response. unknown string field [{}]", currentFieldName);
                }
            } else if (token == XContentParser.Token.START_OBJECT) {
                String headerName = null;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        headerName = parser.currentName();
                    } else if (headerName == null){
                        throw new ParseException("could not parse http response. expected a header name but found [{}] instead", token);
                    } else if (token.isValue()) {
                        headers.put(headerName, new String[] { String.valueOf(parser.objectText()) });
                    } else if (token == XContentParser.Token.START_ARRAY) {
                        List<String> values = new ArrayList<>();
                        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                            if (!token.isValue()) {
                                throw new ParseException("could not parse http response. expected a header value for header [{}] but found [{}] instead", headerName, token);
                            } else {
                                values.add(String.valueOf(parser.objectText()));
                            }
                        }
                        headers.put(headerName, values.toArray(new String[values.size()]));
                    }
                }
            } else {
                throw new ParseException("could not parse http response. unexpected token [{}]", token);
            }
        }

        if (status < 0) {
            throw new ParseException("could not parse http response. missing required numeric [{}] field holding the response's http status code", Field.STATUS.getPreferredName());
        }
        return new HttpResponse(status, body, headers.build());
    }

    public static class ParseException extends WatcherException {
        public ParseException(String msg, Object... args) {
            super(msg, args);
        }

        public ParseException(String msg, Throwable cause, Object... args) {
            super(msg, cause, args);
        }
    }

    interface Field {
        ParseField STATUS = new ParseField("status");
        ParseField HEADERS = new ParseField("headers");
        ParseField BODY = new ParseField("body");
    }
}