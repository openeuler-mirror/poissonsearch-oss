/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.condition;

import org.elasticsearch.watcher.condition.script.ScriptCondition;
import org.elasticsearch.watcher.condition.simple.AlwaysFalseCondition;
import org.elasticsearch.watcher.condition.simple.AlwaysTrueCondition;

/**
 *
 */
public final class ConditionBuilders {

    private ConditionBuilders() {
    }

    public static AlwaysTrueCondition.SourceBuilder alwaysTrueCondition() {
        return AlwaysTrueCondition.SourceBuilder.INSTANCE;
    }

    public static AlwaysFalseCondition.SourceBuilder alwaysFalseCondition() {
        return AlwaysFalseCondition.SourceBuilder.INSTANCE;
    }

    public static ScriptCondition.SourceBuilder scriptCondition() {
        return new ScriptCondition.SourceBuilder();
    }

    public static ScriptCondition.SourceBuilder scriptCondition(String script) {
        return new ScriptCondition.SourceBuilder().script(script);
    }

}
