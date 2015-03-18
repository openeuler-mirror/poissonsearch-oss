/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.webhook;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.watcher.WatcherSettingsException;
import org.elasticsearch.watcher.actions.Action;
import org.elasticsearch.watcher.actions.ActionException;
import org.elasticsearch.watcher.actions.ActionSettingsException;
import org.elasticsearch.watcher.support.Script;
import org.elasticsearch.watcher.support.Variables;
import org.elasticsearch.watcher.support.http.HttpClient;
import org.elasticsearch.watcher.support.http.HttpMethod;
import org.elasticsearch.watcher.support.http.HttpResponse;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.transform.Transform;
import org.elasticsearch.watcher.transform.TransformRegistry;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.WatchExecutionContext;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.*;

/**
 */
public class WebhookAction extends Action<WebhookAction.Result> {

    public static final String TYPE = "webhook";

    private final HttpClient httpClient;

    final HttpMethod method;
    final Template url;
    final @Nullable Template body;

    public WebhookAction(ESLogger logger, @Nullable Transform transform, HttpClient httpClient, HttpMethod method, Template url, Template body) {
        super(logger, transform);
        this.httpClient = httpClient;
        this.method = method;
        this.url = url;
        this.body = body;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    protected Result execute(WatchExecutionContext ctx, Payload payload) throws IOException {
        Map<String, Object> model = Variables.createCtxModel(ctx, payload);
        Map<String, Object> urlSafeModel = new HashMap<>(model.size());

        for (Map.Entry<String, Object> entry : model.entrySet()) {
            urlSafeModel.put(entry.getKey(), makeURLSafe(entry.getValue()));
        }

        String urlText = url.render(urlSafeModel);
        String bodyText = body != null ? body.render(model) : ""; //If body is null send an empty body
        try {
            try (HttpResponse response = httpClient.execute(method, urlText, bodyText)) {
                int status = response.status();
                if (status >= 400) {
                    logger.warn("got status [" + status + "] when connecting to [" + urlText + "]");
                } else {
                    if (status >= 300) {
                        logger.warn("a 200 range return code was expected, but got [" + status + "]");
                    }
                }
                return new Result.Executed(status, urlText, bodyText);
            }
        } catch (IOException ioe) {
            logger.error("failed to connect to [{}] for watch [{}]", ioe, urlText, ctx.watch().name());
            return new Result.Failure("failed to send http request. " + ioe.getMessage());
        }
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (transform != null) {
            builder.startObject(Transform.Parser.TRANSFORM_FIELD.getPreferredName())
                    .field(transform.type(), transform)
                    .endObject();
        }
        builder.field(Parser.METHOD_FIELD.getPreferredName(), method.method());
        builder.field(Parser.URL_FIELD.getPreferredName(), url);
        if (body != null) {
            builder.field(Parser.BODY_FIELD.getPreferredName(), body);
        }
        return builder.endObject();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        WebhookAction that = (WebhookAction) o;

        if (body != null ? !body.equals(that.body) : that.body != null) return false;
        if (!method.equals(that.method)) return false;
        if (!url.equals(that.url)) return false;
        if (transform != null ? !transform.equals(that.transform) : that.transform != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = method.hashCode();
        result = 31 * result + url.hashCode();
        result = 31 * result + (body != null ? body.hashCode() : 0);
        result = 31 * result + (transform != null ? transform.hashCode() : 0);
        return result;
    }

    public abstract static class Result extends Action.Result {

        public Result(String type, boolean success) {
            super(type, success);
        }

        void transformResult(Transform.Result result) {
            this.transformResult = result;
        }

        public static class Executed extends Result {

            private final int httpStatus;
            private final String url;
            private final String body;

            public Executed(int httpStatus, String url, String body) {
                super(TYPE, httpStatus < 400);
                this.httpStatus = httpStatus;
                this.url = url;
                this.body = body;
            }

            public int httpStatus() {
                return httpStatus;
            }

            public String url() {
                return url;
            }

            public String body() {
                return body;
            }

            @Override
            protected XContentBuilder xContentBody(XContentBuilder builder, Params params) throws IOException {
                return builder.field(SUCCESS_FIELD.getPreferredName(), success())
                        .field(WebhookAction.Parser.HTTP_STATUS_FIELD.getPreferredName(), httpStatus)
                        .field(WebhookAction.Parser.URL_FIELD.getPreferredName(), url)
                        .field(WebhookAction.Parser.BODY_FIELD.getPreferredName(), body);
            }
        }

        public static class Failure extends Result {

            private final String reason;

            public Failure(String reason) {
                super(TYPE, false);
                this.reason = reason;
            }

            public String reason() {
                return reason;
            }

            @Override
            protected XContentBuilder xContentBody(XContentBuilder builder, Params params) throws IOException {
                return builder.field(WebhookAction.Parser.REASON_FIELD.getPreferredName(), reason);
            }
        }
    }


    public static class Parser extends AbstractComponent implements Action.Parser<Result, WebhookAction> {

        public static final ParseField METHOD_FIELD = new ParseField("method");
        public static final ParseField URL_FIELD = new ParseField("url");
        public static final ParseField BODY_FIELD = new ParseField("body");
        public static final ParseField HTTP_STATUS_FIELD = new ParseField("http_status");
        public static final ParseField REASON_FIELD = new ParseField("reason");

        private final Template.Parser templateParser;
        private final HttpClient httpClient;
        private final TransformRegistry transformRegistry;

        @Inject
        public Parser(Settings settings, Template.Parser templateParser, HttpClient httpClient, TransformRegistry transformRegistry) {
            super(settings);
            this.templateParser = templateParser;
            this.httpClient = httpClient;
            this.transformRegistry = transformRegistry;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public WebhookAction parse(XContentParser parser) throws IOException {
            Transform transform = null;
            HttpMethod method = HttpMethod.POST;
            Template urlTemplate = null;
            Template bodyTemplate = null;

            String currentFieldName = null;
            XContentParser.Token token;

            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if ((token.isValue() || token == XContentParser.Token.START_OBJECT) && currentFieldName != null ) {
                    if (METHOD_FIELD.match(currentFieldName)) {
                        method = HttpMethod.valueOf(parser.text().toUpperCase(Locale.ROOT));
                        if (method != HttpMethod.POST && method != HttpMethod.GET && method != HttpMethod.PUT) {
                            throw new ActionSettingsException("could not parse webhook action. unsupported http method [" + method.method() + "]");
                        }
                    } else if (URL_FIELD.match(currentFieldName)) {
                        try {
                            urlTemplate = templateParser.parse(parser);
                        } catch (Template.Parser.ParseException pe) {
                            throw new WatcherSettingsException("could not parse webhook action [url] template", pe);
                        }
                    } else if (BODY_FIELD.match(currentFieldName)) {
                        try {
                            bodyTemplate = templateParser.parse(parser);
                        } catch (Template.Parser.ParseException pe) {
                            throw new ActionSettingsException("could not parse webhook action [body] template", pe);
                        }
                    } else if (Transform.Parser.TRANSFORM_FIELD.match(currentFieldName)) {
                        transform = transformRegistry.parse(parser);
                    }  else {
                        throw new ActionSettingsException("could not parse webhook action. unexpected field [" + currentFieldName + "]");
                    }
                } else {
                    throw new ActionSettingsException("could not parse webhook action. unexpected token [" + token + "]");
                }
            }

            if (urlTemplate == null) {
                throw new ActionSettingsException("could not parse webhook action. [url_template] is required");
            }

            return new WebhookAction(logger, transform, httpClient, method, urlTemplate, bodyTemplate);
        }

        @Override
        public Result parseResult(XContentParser parser) throws IOException {
            Transform.Result transformResult = null;
            String currentFieldName = null;
            XContentParser.Token token;
            Boolean success = null;
            String url = null;
            String body = null;
            String reason = null;
            int httpStatus = -1;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token.isValue()) {
                    if (URL_FIELD.match(currentFieldName)) {
                        url = parser.text();
                    } else if (BODY_FIELD.match(currentFieldName)) {
                        body = parser.text();
                    } else if (HTTP_STATUS_FIELD.match(currentFieldName)) {
                        httpStatus = parser.intValue();
                    } else if (REASON_FIELD.match(currentFieldName)) {
                        reason = parser.text();
                    } else if (token == XContentParser.Token.VALUE_BOOLEAN) {
                        if (Action.Result.SUCCESS_FIELD.match(currentFieldName)) {
                            success = parser.booleanValue();
                        } else {
                            throw new ActionException("could not parse webhook result. unexpected boolean field [" + currentFieldName + "]");
                        }
                    } else {
                        throw new ActionException("unable to parse webhook action result. unexpected field [" + currentFieldName + "]");
                    }
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (Transform.Parser.TRANSFORM_RESULT_FIELD.match(currentFieldName)) {
                        transformResult = transformRegistry.parseResult(parser);
                    }
                } else {
                    throw new ActionException("unable to parse webhook action result. unexpected field [" + currentFieldName + "]" );
                }
            }

            if (success == null) {
                throw new ActionException("could not parse webhook result. expected boolean field [success]");
            }


            Result result = (reason == null) ? new Result.Executed(httpStatus, url, body) : new Result.Failure(reason);
            if (transformResult != null) {
                result.transformResult(transformResult);
            }
            return result;
        }
    }

    private Object makeURLSafe(Object toSafe) throws UnsupportedEncodingException {
        if (toSafe instanceof List) {
            List<Object> returnObject = new ArrayList<>(((List) toSafe).size());
            for (Object o : (List)toSafe) {
                returnObject.add(makeURLSafe(o));
            }
            return returnObject;
        } else if (toSafe instanceof Map) {
            Map<Object, Object> returnObject = new HashMap<>(((Map) toSafe).size());
            for (Object key : ((Map) toSafe).keySet()) {
                returnObject.put(key, makeURLSafe(((Map) toSafe).get(key)));
            }
            return returnObject;
        } else if (toSafe instanceof String) {
            return URLEncoder.encode(toSafe.toString(), Charsets.UTF_8.name());
        } else {
            //Don't know how to convert anything else
            return toSafe;
        }
    }


    public static class SourceBuilder implements Action.SourceBuilder {

        private final Script url;
        private HttpMethod method;
        private Script body = null;

        public SourceBuilder(String url) {
            this(new Script(url));
        }

        public SourceBuilder(Script url) {
            this.url = url;
        }

        public SourceBuilder method(HttpMethod method) {
            this.method = method;
            return this;
        }

        public SourceBuilder body(Script body) {
            this.body = body;
            return this;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field(Parser.URL_FIELD.getPreferredName(), url);
            if (method != null) {
                builder.field(Parser.METHOD_FIELD.getPreferredName(), method.method());
            }
            if (body != null) {
                builder.field(Parser.BODY_FIELD.getPreferredName(), body);
            }
            return builder.endObject();
        }
    }
}
