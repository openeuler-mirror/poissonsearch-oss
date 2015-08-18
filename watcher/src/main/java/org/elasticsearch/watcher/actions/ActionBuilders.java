/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions;

import org.elasticsearch.watcher.actions.email.EmailAction;
import org.elasticsearch.watcher.actions.email.service.EmailTemplate;
import org.elasticsearch.watcher.actions.hipchat.HipChatAction;
import org.elasticsearch.watcher.actions.index.IndexAction;
import org.elasticsearch.watcher.actions.logging.LoggingAction;
import org.elasticsearch.watcher.actions.webhook.WebhookAction;
import org.elasticsearch.watcher.support.http.HttpRequestTemplate;
import org.elasticsearch.watcher.support.template.Template;

/**
 *
 */
public final class ActionBuilders {

    private ActionBuilders() {
    }

    public static EmailAction.Builder emailAction(EmailTemplate.Builder email) {
        return emailAction(email.build());
    }

    public static EmailAction.Builder emailAction(EmailTemplate email) {
        return EmailAction.builder(email);
    }

    public static IndexAction.Builder indexAction(String index, String type) {
        return IndexAction.builder(index, type);
    }

    public static WebhookAction.Builder webhookAction(HttpRequestTemplate.Builder httpRequest) {
        return webhookAction(httpRequest.build());
    }

    public static WebhookAction.Builder webhookAction(HttpRequestTemplate httpRequest) {
        return WebhookAction.builder(httpRequest);
    }

    public static LoggingAction.Builder loggingAction(String text) {
        return loggingAction(Template.inline(text));
    }

    public static LoggingAction.Builder loggingAction(Template.Builder text) {
        return loggingAction(text.build());
    }

    public static LoggingAction.Builder loggingAction(Template text) {
        return LoggingAction.builder(text);
    }

    public static HipChatAction.Builder hipchatAction(String message) {
        return hipchatAction(Template.inline(message));
    }

    public static HipChatAction.Builder hipchatAction(String account, String body) {
        return hipchatAction(account, Template.inline(body));
    }

    public static HipChatAction.Builder hipchatAction(Template.Builder body) {
        return hipchatAction(body.build());
    }

    public static HipChatAction.Builder hipchatAction(String account, Template.Builder body) {
        return hipchatAction(account, body.build());
    }

    public static HipChatAction.Builder hipchatAction(Template body) {
        return hipchatAction(null, body);
    }

    public static HipChatAction.Builder hipchatAction(String account, Template body) {
        return HipChatAction.builder(account, body);
    }
}
