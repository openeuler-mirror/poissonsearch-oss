/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.condition.compare;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.watcher.condition.ConditionFactory;
import org.elasticsearch.xpack.support.clock.Clock;

import java.io.IOException;

/**
 *
 */
public class CompareConditionFactory extends ConditionFactory<CompareCondition, CompareCondition.Result, ExecutableCompareCondition> {

    private final Clock clock;

    @Inject
    public CompareConditionFactory(Settings settings, Clock clock) {
        super(Loggers.getLogger(ExecutableCompareCondition.class, settings));
        this.clock = clock;
    }

    @Override
    public String type() {
        return CompareCondition.TYPE;
    }

    @Override
    public CompareCondition parseCondition(String watchId, XContentParser parser) throws IOException {
        return CompareCondition.parse(watchId, parser);
    }

    @Override
    public ExecutableCompareCondition createExecutable(CompareCondition condition) {
        return new ExecutableCompareCondition(condition, conditionLogger, clock);
    }
}
