/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.http;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.support.RestUtils;
import org.elasticsearch.watcher.support.WatcherDateTimeUtils;
import org.elasticsearch.watcher.support.http.HttpRequest.Field;
import org.elasticsearch.watcher.support.http.auth.HttpAuth;
import org.elasticsearch.watcher.support.http.auth.HttpAuthRegistry;
import org.elasticsearch.watcher.support.text.TextTemplate;
import org.elasticsearch.watcher.support.text.TextTemplateEngine;
import org.jboss.netty.handler.codec.http.HttpHeaders;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static java.util.Collections.unmodifiableMap;

/**
 */
public class HttpRequestTemplate implements ToXContent {

    private final Scheme scheme;
    private final String host;
    private final int port;
    private final HttpMethod method;
    private final TextTemplate path;
    private final Map<String, TextTemplate> params;
    private final Map<String, TextTemplate> headers;
    private final HttpAuth auth;
    private final TextTemplate body;
    private final @Nullable TimeValue connectionTimeout;
    private final @Nullable TimeValue readTimeout;
    private final @Nullable HttpProxy proxy;

    public HttpRequestTemplate(String host, int port, @Nullable Scheme scheme, @Nullable HttpMethod method, @Nullable TextTemplate path,
                               Map<String, TextTemplate> params, Map<String, TextTemplate> headers, HttpAuth auth,
                               TextTemplate body, @Nullable TimeValue connectionTimeout, @Nullable TimeValue readTimeout,
                               @Nullable HttpProxy proxy) {
        this.host = host;
        this.port = port;
        this.scheme = scheme != null ? scheme :Scheme.HTTP;
        this.method = method != null ? method : HttpMethod.GET;
        this.path = path;
        this.params = params != null ? params : emptyMap();
        this.headers = headers != null ? headers : emptyMap();
        this.auth = auth;
        this.body = body;
        this.connectionTimeout = connectionTimeout;
        this.readTimeout = readTimeout;
        this.proxy = proxy;
    }

    public Scheme scheme() {
        return scheme;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public HttpMethod method() {
        return method;
    }

    public TextTemplate path() {
        return path;
    }

    public Map<String, TextTemplate> params() {
        return params;
    }

    public Map<String, TextTemplate> headers() {
        return headers;
    }

    public HttpAuth auth() {
        return auth;
    }

    public TextTemplate body() {
        return body;
    }

    public TimeValue connectionTimeout() {
        return connectionTimeout;
    }

    public TimeValue readTimeout() {
        return readTimeout;
    }

    public HttpProxy proxy() {
        return proxy;
    }

    public HttpRequest render(TextTemplateEngine engine, Map<String, Object> model) {
        HttpRequest.Builder request = HttpRequest.builder(host, port);
        request.method(method);
        request.scheme(scheme);
        if (path != null) {
            request.path(engine.render(path, model));
        }
        if (params != null && !params.isEmpty()) {
            MapBuilder<String, String> mapBuilder = MapBuilder.newMapBuilder();
            for (Map.Entry<String, TextTemplate> entry : params.entrySet()) {
                mapBuilder.put(entry.getKey(), engine.render(entry.getValue(), model));
            }
            request.setParams(mapBuilder.map());
        }
        if ((headers == null || headers.isEmpty()) && body != null && body.getContentType() != null) {
            request.setHeaders(singletonMap(HttpHeaders.Names.CONTENT_TYPE, body.getContentType().mediaType()));
        } else if (headers != null && !headers.isEmpty()) {
            MapBuilder<String, String> mapBuilder = MapBuilder.newMapBuilder();
            if (body != null && body.getContentType() != null) {
                // putting the content type first, so it can be overridden by custom headers
                mapBuilder.put(HttpHeaders.Names.CONTENT_TYPE, body.getContentType().mediaType());
            }
            for (Map.Entry<String, TextTemplate> entry : headers.entrySet()) {
                mapBuilder.put(entry.getKey(), engine.render(entry.getValue(), model));
            }
            request.setHeaders(mapBuilder.map());
        }
        if (auth != null) {
            request.auth(auth);
        }
        if (body != null) {
            request.body(engine.render(body, model));
        }
        if (connectionTimeout != null) {
            request.connectionTimeout(connectionTimeout);
        }
        if (readTimeout != null) {
            request.readTimeout(readTimeout);
        }
        if (proxy != null) {
            request.proxy(proxy);
        }
        return request.build();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        builder.startObject();
        builder.field(Field.SCHEME.getPreferredName(), scheme, params);
        builder.field(Field.HOST.getPreferredName(), host);
        builder.field(Field.PORT.getPreferredName(), port);
        builder.field(Field.METHOD.getPreferredName(), method, params);
        if (path != null) {
            builder.field(Field.PATH.getPreferredName(), path, params);
        }
        if (this.params != null) {
            builder.startObject(Field.PARAMS.getPreferredName());
            for (Map.Entry<String, TextTemplate> entry : this.params.entrySet()) {
                builder.field(entry.getKey(), entry.getValue(), params);
            }
            builder.endObject();
        }
        if (headers != null) {
            builder.startObject(Field.HEADERS.getPreferredName());
            for (Map.Entry<String, TextTemplate> entry : headers.entrySet()) {
                builder.field(entry.getKey(), entry.getValue(), params);
            }
            builder.endObject();
        }
        if (auth != null) {
            builder.startObject(Field.AUTH.getPreferredName())
                    .field(auth.type(), auth, params)
                    .endObject();
        }
        if (body != null) {
            builder.field(Field.BODY.getPreferredName(), body, params);
        }
        if (connectionTimeout != null) {
            builder.field(Field.CONNECTION_TIMEOUT.getPreferredName(), connectionTimeout);
        }
        if (readTimeout != null) {
            builder.field(Field.READ_TIMEOUT.getPreferredName(), readTimeout);
        }
        if (proxy != null) {
            proxy.toXContent(builder, params);
        }
        return builder.endObject();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        HttpRequestTemplate that = (HttpRequestTemplate) o;

        if (port != that.port) return false;
        if (scheme != that.scheme) return false;
        if (host != null ? !host.equals(that.host) : that.host != null) return false;
        if (method != that.method) return false;
        if (path != null ? !path.equals(that.path) : that.path != null) return false;
        if (params != null ? !params.equals(that.params) : that.params != null) return false;
        if (headers != null ? !headers.equals(that.headers) : that.headers != null) return false;
        if (auth != null ? !auth.equals(that.auth) : that.auth != null) return false;
        if (connectionTimeout != null ? !connectionTimeout.equals(that.connectionTimeout) : that.connectionTimeout != null) return false;
        if (readTimeout != null ? !readTimeout.equals(that.readTimeout) : that.readTimeout != null) return false;
        if (proxy != null ? !proxy.equals(that.proxy) : that.proxy != null) return false;
        return body != null ? body.equals(that.body) : that.body == null;
    }

    @Override
    public int hashCode() {
        int result = scheme != null ? scheme.hashCode() : 0;
        result = 31 * result + (host != null ? host.hashCode() : 0);
        result = 31 * result + port;
        result = 31 * result + (method != null ? method.hashCode() : 0);
        result = 31 * result + (path != null ? path.hashCode() : 0);
        result = 31 * result + (params != null ? params.hashCode() : 0);
        result = 31 * result + (headers != null ? headers.hashCode() : 0);
        result = 31 * result + (auth != null ? auth.hashCode() : 0);
        result = 31 * result + (body != null ? body.hashCode() : 0);
        result = 31 * result + (connectionTimeout != null ? connectionTimeout.hashCode() : 0);
        result = 31 * result + (readTimeout != null ? readTimeout.hashCode() : 0);
        result = 31 * result + (proxy != null ? proxy.hashCode() : 0);
        return result;
    }

    public static Builder builder(String host, int port) {
        return new Builder(host, port);
    }

    public static class Parser {

        private final HttpAuthRegistry httpAuthRegistry;

        @Inject
        public Parser(HttpAuthRegistry httpAuthRegistry) {
            this.httpAuthRegistry = httpAuthRegistry;
        }

        public HttpRequestTemplate parse(XContentParser parser) throws IOException {
            assert parser.currentToken() == XContentParser.Token.START_OBJECT;

            Builder builder = new Builder();
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.PROXY)) {
                    builder.proxy(HttpProxy.parse(parser));
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.PATH)) {
                    builder.path(parseFieldTemplate(currentFieldName, parser));
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.HEADERS)) {
                    builder.putHeaders(parseFieldTemplates(currentFieldName, parser));
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.PARAMS)) {
                    builder.putParams(parseFieldTemplates(currentFieldName, parser));
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.BODY)) {
                    builder.body(parseFieldTemplate(currentFieldName, parser));
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.URL)) {
                    builder.fromUrl(parser.text());
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.CONNECTION_TIMEOUT)) {
                    try {
                        builder.connectionTimeout(WatcherDateTimeUtils.parseTimeValue(parser, Field.CONNECTION_TIMEOUT.toString()));
                    } catch (ElasticsearchParseException pe) {
                        throw new ElasticsearchParseException("could not parse http request template. invalid time value for [{}] field",
                                pe, currentFieldName);
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.READ_TIMEOUT)) {
                    try {
                        builder.readTimeout(WatcherDateTimeUtils.parseTimeValue(parser, Field.READ_TIMEOUT.toString()));
                    } catch (ElasticsearchParseException pe) {
                        throw new ElasticsearchParseException("could not parse http request template. invalid time value for [{}] field",
                                pe, currentFieldName);
                    }
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.AUTH)) {
                        builder.auth(httpAuthRegistry.parse(parser));
                    }  else {
                        throw new ElasticsearchParseException("could not parse http request template. unexpected object field [{}]",
                                currentFieldName);
                    }
                } else if (token == XContentParser.Token.VALUE_STRING) {
                    if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.SCHEME)) {
                        builder.scheme(Scheme.parse(parser.text()));
                    } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.METHOD)) {
                        builder.method(HttpMethod.parse(parser.text()));
                    } else if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.HOST)) {
                        builder.host = parser.text();
                    } else {
                        throw new ElasticsearchParseException("could not parse http request template. unexpected string field [{}]",
                                currentFieldName);
                    }
                } else if (token == XContentParser.Token.VALUE_NUMBER) {
                    if (ParseFieldMatcher.STRICT.match(currentFieldName, Field.PORT)) {
                        builder.port = parser.intValue();
                    } else {
                        throw new ElasticsearchParseException("could not parse http request template. unexpected numeric field [{}]",
                                currentFieldName);
                    }
                } else {
                    throw new ElasticsearchParseException("could not parse http request template. unexpected token [{}] for field [{}]",
                            token, currentFieldName);
                }
            }

            if (builder.host == null) {
                throw new ElasticsearchParseException("could not parse http request template. missing required [{}] string field",
                        Field.HOST.getPreferredName());
            }
            if (builder.port <= 0) {
                throw new ElasticsearchParseException("could not parse http request template. wrong port for [{}]",
                        Field.PORT.getPreferredName());
            }

            return builder.build();
        }

        private static TextTemplate parseFieldTemplate(String field, XContentParser parser) throws IOException {
            try {
                return TextTemplate.parse(parser);
            } catch (ElasticsearchParseException pe) {
                throw new ElasticsearchParseException("could not parse http request template. could not parse value for [{}] field", pe,
                        field);
            }
        }

        private static Map<String, TextTemplate> parseFieldTemplates(String field, XContentParser parser) throws IOException {
            Map<String, TextTemplate> templates = new HashMap<>();

            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else {
                    templates.put(currentFieldName, parseFieldTemplate(field, parser));
                }
            }
            return templates;
        }
    }

    public static class Builder {

        private String host;
        private int port;
        private Scheme scheme;
        private HttpMethod method;
        private TextTemplate path;
        private final Map<String, TextTemplate> params = new HashMap<>();
        private final Map<String, TextTemplate> headers = new HashMap<>();
        private HttpAuth auth;
        private TextTemplate body;
        private TimeValue connectionTimeout;
        private TimeValue readTimeout;
        private HttpProxy proxy;

        private Builder() {
        }

        private Builder(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public Builder scheme(Scheme scheme) {
            this.scheme = scheme;
            return this;
        }

        public Builder method(HttpMethod method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            return path(TextTemplate.inline(path));
        }

        public Builder path(TextTemplate.Builder path) {
            return path(path.build());
        }

        public Builder path(TextTemplate path) {
            this.path = path;
            return this;
        }

        public Builder putParams(Map<String, TextTemplate> params) {
            this.params.putAll(params);
            return this;
        }

        public Builder putParam(String key, TextTemplate.Builder value) {
            return putParam(key, value.build());
        }

        public Builder putParam(String key, TextTemplate value) {
            this.params.put(key, value);
            return this;
        }

        public Builder putHeaders(Map<String, TextTemplate> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public Builder putHeader(String key, TextTemplate.Builder value) {
            return putHeader(key, value.build());
        }

        public Builder putHeader(String key, TextTemplate value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder auth(HttpAuth auth) {
            this.auth = auth;
            return this;
        }

        public Builder body(String body) {
            return body(TextTemplate.inline(body));
        }

        public Builder body(TextTemplate.Builder body) {
            return body(body.build());
        }

        public Builder body(TextTemplate body) {
            this.body = body;
            return this;
        }

        public Builder body(XContentBuilder content) {
            return body(TextTemplate.inline(content));
        }

        public Builder connectionTimeout(TimeValue timeout) {
            this.connectionTimeout = timeout;
            return this;
        }

        public Builder readTimeout(TimeValue timeout) {
            this.readTimeout = timeout;
            return this;
        }

        public Builder proxy(HttpProxy proxy) {
            this.proxy = proxy;
            return this;
        }

        public HttpRequestTemplate build() {
            return new HttpRequestTemplate(host, port, scheme, method, path, unmodifiableMap(new HashMap<>(params)),
                    unmodifiableMap(new HashMap<>(headers)), auth, body, connectionTimeout, readTimeout, proxy);
        }

        public Builder fromUrl(String supposedUrl) {
            try {
                URI uri = new URI(supposedUrl);
                port = uri.getPort() > 0 ? uri.getPort() : 80;
                host = uri.getHost();
                scheme = Scheme.parse(uri.getScheme());
                if (Strings.hasLength(uri.getPath())) {
                    path = TextTemplate.inline(uri.getPath()).build();
                }

                String rawQuery = uri.getRawQuery();
                if (Strings.hasLength(rawQuery)) {
                    Map<String, String> stringParams = new HashMap<>();
                    RestUtils.decodeQueryString(rawQuery, 0, stringParams);
                    for (Map.Entry<String, String> entry : stringParams.entrySet()) {
                        params.put(entry.getKey(), TextTemplate.inline(entry.getValue()).build());
                    }
                }
            } catch (URISyntaxException e) {
                throw new ElasticsearchParseException("Malformed URI [{}]", supposedUrl);
            }
            return this;
        }
    }

}
