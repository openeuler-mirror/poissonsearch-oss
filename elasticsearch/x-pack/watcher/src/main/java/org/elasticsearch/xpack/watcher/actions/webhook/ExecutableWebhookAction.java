/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.actions.webhook;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.xpack.common.text.TextTemplateEngine;
import org.elasticsearch.xpack.watcher.actions.Action;
import org.elasticsearch.xpack.watcher.actions.ExecutableAction;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.support.Variables;
import org.elasticsearch.xpack.common.http.HttpClient;
import org.elasticsearch.xpack.common.http.HttpRequest;
import org.elasticsearch.xpack.common.http.HttpResponse;
import org.elasticsearch.xpack.watcher.watch.Payload;

import java.util.Map;

/**
 */
public class ExecutableWebhookAction extends ExecutableAction<WebhookAction> {

    private final HttpClient httpClient;
    private final TextTemplateEngine templateEngine;

    public ExecutableWebhookAction(WebhookAction action, ESLogger logger, HttpClient httpClient, TextTemplateEngine templateEngine) {
        super(action, logger);
        this.httpClient = httpClient;
        this.templateEngine = templateEngine;
    }

    @Override
    public Action.Result execute(String actionId, WatchExecutionContext ctx, Payload payload) throws Exception {
        Map<String, Object> model = Variables.createCtxModel(ctx, payload);

        HttpRequest request = action.requestTemplate.render(templateEngine, model);

        if (ctx.simulateAction(actionId)) {
            return new WebhookAction.Result.Simulated(request);
        }

        HttpResponse response = httpClient.execute(request);

        int status = response.status();
        if (status >= 400) {
            logger.warn("received http status [{}] when connecting to watch action [{}/{}/{}]", status, ctx.watch().id(), type(), actionId);
            return new WebhookAction.Result.Failure(request, response);
        }
        return new WebhookAction.Result.Success(request, response);
    }
}
