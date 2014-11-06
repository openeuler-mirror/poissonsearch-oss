/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.triggers;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;

/**
 */
public class TriggerResult {

    private final boolean triggered;
    private final SearchRequest request;
    private final SearchResponse response;
    private final AlertTrigger trigger;

    public TriggerResult(boolean triggered, SearchRequest request, SearchResponse response, AlertTrigger trigger) {
        this.triggered = triggered;
        this.request = request;
        this.response = response;
        this.trigger = trigger;
    }

    public boolean isTriggered() {
        return triggered;
    }

    public SearchRequest getRequest() {
        return request;
    }

    public SearchResponse getResponse() {
        return response;
    }

    public AlertTrigger getTrigger() {
        return trigger;
    }

}
