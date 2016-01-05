/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions;

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.multibindings.MapBinder;
import org.elasticsearch.watcher.actions.email.EmailAction;
import org.elasticsearch.watcher.actions.email.EmailActionFactory;
import org.elasticsearch.watcher.actions.email.service.EmailService;
import org.elasticsearch.watcher.actions.email.service.HtmlSanitizer;
import org.elasticsearch.watcher.actions.email.service.InternalEmailService;
import org.elasticsearch.watcher.actions.hipchat.HipChatAction;
import org.elasticsearch.watcher.actions.hipchat.HipChatActionFactory;
import org.elasticsearch.watcher.actions.hipchat.service.HipChatService;
import org.elasticsearch.watcher.actions.hipchat.service.InternalHipChatService;
import org.elasticsearch.watcher.actions.index.IndexAction;
import org.elasticsearch.watcher.actions.index.IndexActionFactory;
import org.elasticsearch.watcher.actions.logging.LoggingAction;
import org.elasticsearch.watcher.actions.logging.LoggingActionFactory;
import org.elasticsearch.watcher.actions.pagerduty.PagerDutyAction;
import org.elasticsearch.watcher.actions.pagerduty.PagerDutyActionFactory;
import org.elasticsearch.watcher.actions.pagerduty.service.InternalPagerDutyService;
import org.elasticsearch.watcher.actions.pagerduty.service.PagerDutyService;
import org.elasticsearch.watcher.actions.slack.SlackAction;
import org.elasticsearch.watcher.actions.slack.SlackActionFactory;
import org.elasticsearch.watcher.actions.slack.service.InternalSlackService;
import org.elasticsearch.watcher.actions.slack.service.SlackService;
import org.elasticsearch.watcher.actions.webhook.WebhookAction;
import org.elasticsearch.watcher.actions.webhook.WebhookActionFactory;

import java.util.HashMap;
import java.util.Map;

/**
 */
public class WatcherActionModule extends AbstractModule {

    private final Map<String, Class<? extends ActionFactory>> parsers = new HashMap<>();

    public WatcherActionModule() {
        registerAction(EmailAction.TYPE, EmailActionFactory.class);
        registerAction(WebhookAction.TYPE, WebhookActionFactory.class);
        registerAction(IndexAction.TYPE, IndexActionFactory.class);
        registerAction(LoggingAction.TYPE, LoggingActionFactory.class);
        registerAction(HipChatAction.TYPE, HipChatActionFactory.class);
        registerAction(SlackAction.TYPE, SlackActionFactory.class);
        registerAction(PagerDutyAction.TYPE, PagerDutyActionFactory.class);
    }

    public void registerAction(String type, Class<? extends ActionFactory> parserType) {
        parsers.put(type, parserType);
    }

    @Override
    protected void configure() {

        MapBinder<String, ActionFactory> parsersBinder = MapBinder.newMapBinder(binder(), String.class, ActionFactory.class);
        for (Map.Entry<String, Class<? extends ActionFactory>> entry : parsers.entrySet()) {
            bind(entry.getValue()).asEagerSingleton();
            parsersBinder.addBinding(entry.getKey()).to(entry.getValue());
        }

        bind(ActionRegistry.class).asEagerSingleton();

        // email
        bind(HtmlSanitizer.class).asEagerSingleton();
        bind(InternalEmailService.class).asEagerSingleton();
        bind(EmailService.class).to(InternalEmailService.class).asEagerSingleton();

        // hipchat
        bind(InternalHipChatService.class).asEagerSingleton();
        bind(HipChatService.class).to(InternalHipChatService.class);

        // slack
        bind(InternalSlackService.class).asEagerSingleton();
        bind(SlackService.class).to(InternalSlackService.class);

        // pager duty
        bind(InternalPagerDutyService.class).asEagerSingleton();
        bind(PagerDutyService.class).to(InternalPagerDutyService.class);
    }
}
