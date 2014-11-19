/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.rest;

import org.elasticsearch.alerts.AlertsStore;
import org.elasticsearch.alerts.client.AlertsClient;
import org.elasticsearch.alerts.transport.actions.stats.AlertsStatsRequest;
import org.elasticsearch.alerts.transport.actions.stats.AlertsStatsResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestBuilderListener;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestStatus.OK;

/**
 * The RestAction for alerts stats
 */
public class RestAlertsStatsAction extends BaseRestHandler {

    private final AlertsClient alertsClient;

    @Inject
    protected RestAlertsStatsAction(Settings settings, RestController controller, Client client, AlertsClient alertsClient) {
        super(settings, controller, client);
        this.alertsClient = alertsClient;
        controller.registerHandler(GET, AlertsStore.ALERT_INDEX + "/alert/_stats", this);
    }

    @Override
    protected void handleRequest(RestRequest request, RestChannel restChannel, Client client) throws Exception {
        AlertsStatsRequest statsRequest = new AlertsStatsRequest();
        alertsClient.alertsStats(statsRequest, new RestBuilderListener<AlertsStatsResponse>(restChannel) {
            @Override
            public RestResponse buildResponse(AlertsStatsResponse alertsStatsResponse, XContentBuilder builder) throws Exception {
                builder.startObject()
                        .field("alert_manager_started", alertsStatsResponse.isAlertManagerStarted())
                        .field("alert_action_manager_started", alertsStatsResponse.isAlertActionManagerStarted())
                        .field("alert_action_queue_size", alertsStatsResponse.getAlertActionManagerQueueSize())
                        .field("number_of_alerts", alertsStatsResponse.getNumberOfRegisteredAlerts())
                        .field("alert_action_queue_max_size", alertsStatsResponse.getAlertActionManagerLargestQueueSize());
                return new BytesRestResponse(OK, builder);

            }
        });
    }
}
