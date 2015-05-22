/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.condition.compare;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.watcher.condition.ExecutableCondition;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.support.Variables;
import org.elasticsearch.watcher.support.WatcherDateTimeUtils;
import org.elasticsearch.watcher.support.clock.Clock;
import org.elasticsearch.watcher.support.xcontent.MapPath;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;

/**
 *
 */
public class ExecutableCompareCondition extends ExecutableCondition<CompareCondition, CompareCondition.Result> {

    static final Pattern DATE_MATH_PATTERN = Pattern.compile("<\\{(.+)\\}>");
    static final Pattern PATH_PATTERN = Pattern.compile("\\{\\{(.+)\\}\\}");


    private final Clock clock;

    public ExecutableCompareCondition(CompareCondition condition, ESLogger logger, Clock clock) {
        super(condition, logger);
        this.clock = clock;
    }

    @Override
    public CompareCondition.Result execute(WatchExecutionContext ctx) throws IOException {
        Map<String, Object> model = Variables.createCtxModel(ctx, ctx.payload());

        Object configuredValue = condition.getValue();

        if (configuredValue instanceof String) {

            // checking if the given value is a date math expression
            Matcher matcher = DATE_MATH_PATTERN.matcher((String) configuredValue);
            if (matcher.matches()) {
                String dateMath = matcher.group(1);
                configuredValue = WatcherDateTimeUtils.parseDateMath(dateMath, UTC, clock);
            } else {
                // checking if the given value is a path expression
                matcher = PATH_PATTERN.matcher((String) configuredValue);
                if (matcher.matches()) {
                    String configuredPath = matcher.group(1);
                    configuredValue = MapPath.eval(configuredPath, model);
                }
            }
        }

        Object resolvedValue = MapPath.eval(condition.getPath(), model);

        return new CompareCondition.Result(resolvedValue, condition.getOp().eval(resolvedValue, configuredValue));
    }
}
