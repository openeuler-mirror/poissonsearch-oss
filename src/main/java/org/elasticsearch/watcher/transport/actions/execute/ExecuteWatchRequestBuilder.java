/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transport.actions.execute;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.master.MasterNodeOperationRequestBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.watcher.client.WatcherClient;
import org.elasticsearch.watcher.trigger.TriggerEvent;
import org.elasticsearch.watcher.execution.ActionExecutionMode;

import java.io.IOException;
import java.util.Map;

/**
 * A execute watch action request builder.
 */
public class ExecuteWatchRequestBuilder extends MasterNodeOperationRequestBuilder<ExecuteWatchRequest, ExecuteWatchResponse, ExecuteWatchRequestBuilder, Client> {

    public ExecuteWatchRequestBuilder(Client client) {
        super(client, new ExecuteWatchRequest());
    }

    public ExecuteWatchRequestBuilder(Client client, String watchName) {
        super(client, new ExecuteWatchRequest(watchName));
    }

    /**
     * Sets the id of the watch to be executed
     */
    public ExecuteWatchRequestBuilder setId(String id) {
        this.request().setId(id);
        return this;
    }
    /**
    * @param ignoreCondition set if the condition for this execution be ignored
    */
    public ExecuteWatchRequestBuilder setIgnoreCondition(boolean ignoreCondition) {
        request.setIgnoreCondition(ignoreCondition);
        return this;
    }

    /**
     * @param ignoreThrottle Sets if the throttle should be ignored for this execution
     */
    public ExecuteWatchRequestBuilder setIgnoreThrottle(boolean ignoreThrottle) {
        request.setIgnoreThrottle(ignoreThrottle);
        return this;
    }

    /**
     * @param recordExecution Sets if this execution be recorded in the history index and reflected in the watch
     */
    public ExecuteWatchRequestBuilder setRecordExecution(boolean recordExecution) {
        request.setRecordExecution(recordExecution);
        return this;
    }

    /**
     * @param alternativeInput Set's the alernative input
     */
    public ExecuteWatchRequestBuilder setAlternativeInput(Map<String, Object> alternativeInput) {
        request.setAlternativeInput(alternativeInput);
        return this;
    }

    /**
     * @param triggerType the trigger type to use
     * @param triggerSource the trigger source to use
     */
    public ExecuteWatchRequestBuilder setTriggerEvent(String triggerType, BytesReference triggerSource) {
        request.setTriggerEvent(triggerType, triggerSource);
        return this;
    }

    /**
     * @param triggerEvent the trigger event to use
     */
    public ExecuteWatchRequestBuilder setTriggerEvent(TriggerEvent triggerEvent) throws IOException {
        request.setTriggerEvent(triggerEvent);
        return this;
    }

    /**
     * Sets the mode in which the given action (identified by its id) will be handled.
     *
     * @param actionId      The id of the action
     * @param actionMode    The mode in which the action will be handled in the execution
     */
    public ExecuteWatchRequestBuilder setActionMode(String actionId, ActionExecutionMode actionMode) {
        request.setActionMode(actionId, actionMode);
        return this;
    }

    @Override
    protected void doExecute(final ActionListener<ExecuteWatchResponse> listener) {
        new WatcherClient(client).executeWatch(request, listener);
    }

}
