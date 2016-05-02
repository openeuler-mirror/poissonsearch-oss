/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.input.http;


import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.input.ExecutableInput;
import org.elasticsearch.xpack.watcher.support.Variables;
import org.elasticsearch.xpack.watcher.support.XContentFilterKeysUtils;
import org.elasticsearch.xpack.watcher.support.http.HttpClient;
import org.elasticsearch.xpack.watcher.support.http.HttpRequest;
import org.elasticsearch.xpack.watcher.support.http.HttpResponse;
import org.elasticsearch.xpack.watcher.support.text.TextTemplateEngine;
import org.elasticsearch.xpack.watcher.watch.Payload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 */
public class ExecutableHttpInput extends ExecutableInput<HttpInput, HttpInput.Result> {

    private final HttpClient client;
    private final TextTemplateEngine templateEngine;

    public ExecutableHttpInput(HttpInput input, ESLogger logger, HttpClient client, TextTemplateEngine templateEngine) {
        super(input, logger);
        this.client = client;
        this.templateEngine = templateEngine;
    }

    public HttpInput.Result execute(WatchExecutionContext ctx, Payload payload) {
        HttpRequest request = null;
        try {
            Map<String, Object> model = Variables.createCtxModel(ctx, payload);
            request = input.getRequest().render(templateEngine, model);
            return doExecute(ctx, request);
        } catch (Exception e) {
            logger.error("failed to execute [{}] input for [{}]", e, HttpInput.TYPE, ctx.watch());
            return new HttpInput.Result(request, e);
        }
    }

    HttpInput.Result doExecute(WatchExecutionContext ctx, HttpRequest request) throws Exception {
        HttpResponse response = client.execute(request);
        Map<String, List<String>> headers = response.headers();

        if (!response.hasContent()) {
            Payload payload = headers.size() > 0 ? new Payload.Simple("_headers", headers) : Payload.EMPTY;
            return new HttpInput.Result(request, -1, payload);
        }

        XContentType contentType = response.xContentType();
        if (input.getExpectedResponseXContentType() != null) {
            if (contentType != input.getExpectedResponseXContentType().contentType()) {
                logger.warn("[{}] [{}] input expected content type [{}] but read [{}] from headers", type(), ctx.id(),
                        input.getExpectedResponseXContentType(), contentType);
            }
            if (contentType == null) {
                contentType = input.getExpectedResponseXContentType().contentType();
            }
        } else {
            //Attempt to auto detect content type
            if (contentType == null) {
                contentType = XContentFactory.xContentType(response.body());
            }
        }

        XContentParser parser = null;
        if (contentType != null) {
            try {
                parser = contentType.xContent().createParser(response.body());
            } catch (Exception e) {
                throw new ElasticsearchParseException("could not parse response body [{}] it does not appear to be [{}]", type(), ctx.id(),
                        response.body().toUtf8(), contentType.shortName());
            }
        }

        final Map<String, Object> payloadMap = new HashMap<>();
        if (input.getExtractKeys() != null) {
            payloadMap.putAll(XContentFilterKeysUtils.filterMapOrdered(input.getExtractKeys(), parser));
        } else {
            if (parser != null) {
                payloadMap.putAll(parser.mapOrdered());
            } else {
                payloadMap.put("_value", response.body().toUtf8());
            }
        }
        if (headers.size() > 0) {
            payloadMap.put("_headers", headers);
        }
        return new HttpInput.Result(request, response.status(), new Payload.Simple(payloadMap));
    }
}
