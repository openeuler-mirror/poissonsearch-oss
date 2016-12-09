/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.rest.results;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.prelert.PrelertPlugin;
import org.elasticsearch.xpack.prelert.action.GetCategoriesDefinitionAction;
import org.elasticsearch.xpack.prelert.action.GetCategoriesDefinitionAction.Request;
import org.elasticsearch.xpack.prelert.job.Job;
import org.elasticsearch.xpack.prelert.job.results.PageParams;

import java.io.IOException;

public class RestGetCategoriesAction extends BaseRestHandler {

    private final GetCategoriesDefinitionAction.TransportAction transportAction;

    @Inject
    public RestGetCategoriesAction(Settings settings, RestController controller,
                                   GetCategoriesDefinitionAction.TransportAction transportAction) {
        super(settings);
        this.transportAction = transportAction;
        controller.registerHandler(RestRequest.Method.GET,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/categorydefinitions/{"
                + Request.CATEGORY_ID.getPreferredName() + "}", this);
        controller.registerHandler(RestRequest.Method.GET,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/categorydefinitions", this);

        controller.registerHandler(RestRequest.Method.POST,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/categorydefinitions/{"
                + Request.CATEGORY_ID.getPreferredName() + "}", this);
        controller.registerHandler(RestRequest.Method.POST,
                PrelertPlugin.BASE_PATH + "anomaly_detectors/{" + Job.ID.getPreferredName() + "}/results/categorydefinitions", this);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest restRequest, NodeClient client) throws IOException {
        Request request = new Request(restRequest.param(Job.ID.getPreferredName()));

        String categoryId = restRequest.param(Request.CATEGORY_ID.getPreferredName());
        if (categoryId != null && !categoryId.isEmpty()) {
            request.setCategoryId(categoryId);
        } else {
            PageParams pageParams = new PageParams(
                    restRequest.paramAsInt(Request.FROM.getPreferredName(), 0),
                    restRequest.paramAsInt(Request.SIZE.getPreferredName(), 100)
            );
            request.setPageParams(pageParams);
        }

        return channel -> transportAction.execute(request, new RestToXContentListener<>(channel));
    }

}
