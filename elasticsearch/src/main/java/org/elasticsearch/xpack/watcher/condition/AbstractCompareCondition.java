/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.condition;

import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.support.Variables;
import org.elasticsearch.xpack.watcher.support.WatcherDateTimeUtils;
import org.elasticsearch.xpack.watcher.support.xcontent.ObjectPath;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractCompareCondition
        extends Condition {
    static final Pattern DATE_MATH_PATTERN = Pattern.compile("<\\{(.+)\\}>");
    static final Pattern PATH_PATTERN = Pattern.compile("\\{\\{(.+)\\}\\}");

    private final Clock clock;

    protected AbstractCompareCondition(String type, Clock clock) {
        super(type);
        this.clock = clock;
    }

    @Override
    public final Result execute(WatchExecutionContext ctx) {
        Map<String, Object> resolvedValues = new HashMap<>();
        Map<String, Object> model = Variables.createCtxModel(ctx, ctx.payload());
        return doExecute(model, resolvedValues);
    }

    protected Object resolveConfiguredValue(Map<String, Object> resolvedValues, Map<String, Object> model, Object configuredValue) {
        if (configuredValue instanceof String) {

            // checking if the given value is a date math expression
            Matcher matcher = DATE_MATH_PATTERN.matcher((String) configuredValue);
            if (matcher.matches()) {
                String dateMath = matcher.group(1);
                configuredValue = WatcherDateTimeUtils.parseDateMath(dateMath, DateTimeZone.UTC, clock);
                resolvedValues.put(dateMath, WatcherDateTimeUtils.formatDate((DateTime) configuredValue));
            } else {
                // checking if the given value is a path expression
                matcher = PATH_PATTERN.matcher((String) configuredValue);
                if (matcher.matches()) {
                    String configuredPath = matcher.group(1);
                    configuredValue = ObjectPath.eval(configuredPath, model);
                    resolvedValues.put(configuredPath, configuredValue);
                }
            }
        }
        return configuredValue;
    }

    protected abstract Result doExecute(Map<String, Object> model, Map<String, Object> resolvedValues);
}
