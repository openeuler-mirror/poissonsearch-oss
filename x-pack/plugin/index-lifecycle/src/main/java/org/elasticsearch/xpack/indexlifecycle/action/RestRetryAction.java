/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 *
 */
package org.elasticsearch.xpack.indexlifecycle.action;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.core.indexlifecycle.action.RetryAction;
import org.elasticsearch.xpack.indexlifecycle.IndexLifecycle;

public class RestRetryAction extends BaseRestHandler {

    public RestRetryAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(RestRequest.Method.POST, IndexLifecycle.BASE_PATH + "_retry/{index}", this);
    }

    @Override
    public String getName() {
        return "xpack_lifecycle_retry_action";
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) {
        String[] indices = Strings.splitStringByCommaToArray(restRequest.param("index"));
        RetryAction.Request request = new RetryAction.Request(indices);
        request.timeout(restRequest.paramAsTime("timeout", request.timeout()));
        request.masterNodeTimeout(restRequest.paramAsTime("master_timeout", request.masterNodeTimeout()));
        return channel -> client.execute(RetryAction.INSTANCE, request, new RestToXContentListener<>(channel));
    }
}
