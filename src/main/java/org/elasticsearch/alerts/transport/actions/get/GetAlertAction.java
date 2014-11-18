/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.transport.actions.get;

import org.elasticsearch.alerts.client.AlertsClientAction;
import org.elasticsearch.alerts.client.AlertsClient;

/**
 * This action gets an alert by name
 */
public class GetAlertAction extends AlertsClientAction<GetAlertRequest, GetAlertResponse, GetAlertRequestBuilder> {

    public static final GetAlertAction INSTANCE = new GetAlertAction();
    public static final String NAME = "indices:data/read/alert/get";

    private GetAlertAction() {
        super(NAME);
    }

    @Override
    public GetAlertResponse newResponse() {
        return new GetAlertResponse();
    }

    @Override
    public GetAlertRequestBuilder newRequestBuilder(AlertsClient client) {
        return new GetAlertRequestBuilder(client);
    }
}
