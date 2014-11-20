/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.transport.actions.ack;

import org.elasticsearch.alerts.client.AlertsClient;
import org.elasticsearch.alerts.client.AlertsClientAction;

/**
 * This action acks an alert in memory, and the index
 */
public class AckAlertAction extends AlertsClientAction<AckAlertRequest, AckAlertResponse, AckAlertRequestBuilder> {

    public static final AckAlertAction INSTANCE = new AckAlertAction();
    public static final String NAME = "indices:data/write/alert/ack";

    private AckAlertAction() {
        super(NAME);
    }

    @Override
    public AckAlertResponse newResponse() {
        return new AckAlertResponse();
    }

    @Override
    public AckAlertRequestBuilder newRequestBuilder(AlertsClient client) {
        return new AckAlertRequestBuilder(client);
    }
}
