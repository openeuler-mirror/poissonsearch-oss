/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.input.transform;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.input.ExecutableInput;
import org.elasticsearch.xpack.watcher.transform.ExecutableTransform;
import org.elasticsearch.xpack.watcher.transform.Transform;
import org.elasticsearch.xpack.watcher.watch.Payload;

public final class ExecutableTransformInput extends ExecutableInput<TransformInput, TransformInput.Result> {

    private final ExecutableTransform executableTransform;

    ExecutableTransformInput(TransformInput input, Logger logger, ExecutableTransform executableTransform) {
        super(input, logger);
        this.executableTransform = executableTransform;
    }

    @Override
    public TransformInput.Result execute(WatchExecutionContext ctx, Payload payload) {
        Transform.Result transformResult = executableTransform.execute(ctx, payload);
        return new TransformInput.Result(transformResult.payload());
    }
}
