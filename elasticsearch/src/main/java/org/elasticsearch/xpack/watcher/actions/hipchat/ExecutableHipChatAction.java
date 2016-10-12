/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.actions.hipchat;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.xpack.common.text.TextTemplateEngine;
import org.elasticsearch.xpack.notification.hipchat.HipChatAccount;
import org.elasticsearch.xpack.notification.hipchat.HipChatMessage;
import org.elasticsearch.xpack.notification.hipchat.HipChatService;
import org.elasticsearch.xpack.notification.hipchat.SentMessages;
import org.elasticsearch.xpack.watcher.actions.Action;
import org.elasticsearch.xpack.watcher.actions.ExecutableAction;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.support.Variables;
import org.elasticsearch.xpack.watcher.watch.Payload;

import java.util.Map;

public class ExecutableHipChatAction extends ExecutableAction<HipChatAction> {

    private final TextTemplateEngine templateEngine;
    private final HipChatService hipchatService;

    public ExecutableHipChatAction(HipChatAction action, Logger logger, HipChatService hipchatService,
                                   TextTemplateEngine templateEngine) {
        super(action, logger);
        this.hipchatService = hipchatService;
        this.templateEngine = templateEngine;
    }

    @Override
    public Action.Result execute(final String actionId, WatchExecutionContext ctx, Payload payload) throws Exception {

        HipChatAccount account = action.account != null ?
                hipchatService.getAccount(action.account) :
                hipchatService.getDefaultAccount();

        // lets validate the message again, in case the hipchat service were updated since the
        // watch/action were created.
        account.validateParsedTemplate(ctx.id().watchId(), actionId, action.message);

        Map<String, Object> model = Variables.createCtxModel(ctx, payload);
        HipChatMessage message = account.render(ctx.id().watchId(), actionId, templateEngine, action.message, model);

        if (ctx.simulateAction(actionId)) {
            return new HipChatAction.Result.Simulated(message);
        }

        SentMessages sentMessages = account.send(message, action.proxy);
        return new HipChatAction.Result.Executed(sentMessages);
    }

}
