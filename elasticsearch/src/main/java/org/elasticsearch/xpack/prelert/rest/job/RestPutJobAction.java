/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.rest.job;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.prelert.PrelertPlugin;
import org.elasticsearch.xpack.prelert.action.PutJobAction;
import org.elasticsearch.xpack.prelert.job.Job;

import java.io.IOException;

public class RestPutJobAction extends BaseRestHandler {

    private final PutJobAction.TransportAction transportPutJobAction;

    @Inject
    public RestPutJobAction(Settings settings, RestController controller, PutJobAction.TransportAction transportPutJobAction) {
        super(settings);
        this.transportPutJobAction = transportPutJobAction;
        controller.registerHandler(RestRequest.Method.PUT,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}", this);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        String jobId = restRequest.param(Job.ID.getPreferredName());
        XContentParser parser = restRequest.contentParser();
        PutJobAction.Request putJobRequest = PutJobAction.Request.parseRequest(jobId, parser, () -> parseFieldMatcher);
        boolean overwrite = restRequest.paramAsBoolean("overwrite", false);
        putJobRequest.setOverwrite(overwrite);
        return channel -> transportPutJobAction.execute(putJobRequest, new RestToXContentListener<>(channel));
    }

}
