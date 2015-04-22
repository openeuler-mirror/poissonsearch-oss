/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transform.script;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.support.Script;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.transform.ExecutableTransform;
import org.elasticsearch.watcher.watch.Payload;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.watcher.support.Variables.createCtxModel;

/**
 *
 */
public class ExecutableScriptTransform extends ExecutableTransform<ScriptTransform, ScriptTransform.Result> {

    private final ScriptServiceProxy scriptService;

    public ExecutableScriptTransform(ScriptTransform transform, ESLogger logger, ScriptServiceProxy scriptService) {
        super(transform, logger);
        this.scriptService = scriptService;
    }

    @Override
    public ScriptTransform.Result execute(WatchExecutionContext ctx, Payload payload) throws IOException {
        Script script = transform.getScript();
        Map<String, Object> model = new HashMap<>();
        model.putAll(script.params());
        model.putAll(createCtxModel(ctx, payload));
        ExecutableScript executable = scriptService.executable(script.lang(), script.script(), script.type(), model);
        Object value = executable.run();
        if (value instanceof Map) {
            return new ScriptTransform.Result(new Payload.Simple((Map<String, Object>) value));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("_value", value);
        return new ScriptTransform.Result(new Payload.Simple(data));
    }
}
