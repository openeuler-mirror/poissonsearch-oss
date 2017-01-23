/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.rest.job;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.ml.MlPlugin;
import org.elasticsearch.xpack.ml.action.OpenJobAction;
import org.elasticsearch.xpack.ml.action.PostDataAction;
import org.elasticsearch.xpack.ml.job.config.Job;

import java.io.IOException;

public class RestOpenJobAction extends BaseRestHandler {

    public RestOpenJobAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(RestRequest.Method.POST, MlPlugin.BASE_PATH
                + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/_open", this);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        OpenJobAction.Request request = new OpenJobAction.Request(restRequest.param(Job.ID.getPreferredName()));
        request.setIgnoreDowntime(restRequest.paramAsBoolean(PostDataAction.Request.IGNORE_DOWNTIME.getPreferredName(), false));
        if (restRequest.hasParam("open_timeout")) {
            TimeValue openTimeout = restRequest.paramAsTime("open_timeout", TimeValue.timeValueSeconds(30));
            request.setOpenTimeout(openTimeout);
        }
        return channel -> {
            client.execute(OpenJobAction.INSTANCE, request, new RestToXContentListener<>(channel));
        };
    }
}
