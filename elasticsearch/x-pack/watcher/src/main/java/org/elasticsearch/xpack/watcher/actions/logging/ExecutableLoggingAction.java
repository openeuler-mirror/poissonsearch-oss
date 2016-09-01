/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.actions.logging;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.common.text.TextTemplateEngine;
import org.elasticsearch.xpack.watcher.actions.Action;
import org.elasticsearch.xpack.watcher.actions.ExecutableAction;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.support.Variables;
import org.elasticsearch.xpack.watcher.watch.Payload;

import java.util.Map;

/**
 *
 */
public class ExecutableLoggingAction extends ExecutableAction<LoggingAction> {

    private final Logger textLogger;
    private final TextTemplateEngine templateEngine;

    ExecutableLoggingAction(LoggingAction action, Logger logger, Settings settings, TextTemplateEngine templateEngine) {
        super(action, logger);
        this.textLogger = action.category != null ? Loggers.getLogger(action.category, settings) : logger;
        this.templateEngine = templateEngine;
    }

    // for tests
    ExecutableLoggingAction(LoggingAction action, Logger logger, Logger textLogger, TextTemplateEngine templateEngine) {
        super(action, logger);
        this.textLogger = textLogger;
        this.templateEngine = templateEngine;
    }

    Logger textLogger() {
        return textLogger;
    }

    @Override
    public  Action.Result execute(String actionId, WatchExecutionContext ctx, Payload payload) throws Exception {
        Map<String, Object> model = Variables.createCtxModel(ctx, payload);

        String loggedText = templateEngine.render(action.text, model);
        if (ctx.simulateAction(actionId)) {
            return new LoggingAction.Result.Simulated(loggedText);
        }

        action.level.log(textLogger, loggedText);
        return new LoggingAction.Result.Success(loggedText);
    }
}
