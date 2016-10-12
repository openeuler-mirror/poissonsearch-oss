/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.condition.always;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.xpack.watcher.condition.ExecutableCondition;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;

import java.io.IOException;

public class ExecutableAlwaysCondition extends ExecutableCondition<AlwaysCondition, AlwaysCondition.Result> {

    public ExecutableAlwaysCondition(Logger logger) {
        super(AlwaysCondition.INSTANCE, logger);
    }

    @Override
    public AlwaysCondition.Result execute(WatchExecutionContext ctx) {
        return AlwaysCondition.Result.INSTANCE;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        return builder.startObject().endObject();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ExecutableAlwaysCondition;
    }

    @Override
    public int hashCode() {
        // All instances has to produce the same hashCode because they are all equal
        return 0;
    }
}
