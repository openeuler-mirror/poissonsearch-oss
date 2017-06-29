/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.upgrade.rest;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.BulkByScrollTask;
import org.elasticsearch.index.reindex.ScrollableHitSource;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.elasticsearch.xpack.upgrade.actions.IndexUpgradeAction;
import org.elasticsearch.xpack.upgrade.actions.IndexUpgradeAction.Request;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class RestIndexUpgradeAction extends BaseRestHandler {
    private final Set<String> extraParameters;

    public RestIndexUpgradeAction(Settings settings, RestController controller, Set<String> extraParameters) {
        super(settings);
        controller.registerHandler(RestRequest.Method.POST, "_xpack/migration/upgrade/{index}", this);
        this.extraParameters = extraParameters;
    }

    @Override
    public String getName() {
        return "xpack_migration_upgrade";
    }

    @Override
    public RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        if (request.method().equals(RestRequest.Method.POST)) {
            return handlePost(request, client);
        } else {
            throw new IllegalArgumentException("illegal method [" + request.method() + "] for request [" + request.path() + "]");
        }
    }

    private RestChannelConsumer handlePost(final RestRequest request, NodeClient client) {
        Request upgradeRequest = new Request(request.param("index"));
        Map<String, String> extraParamsMap = new HashMap<>();
        for (String param : extraParameters) {
            String value = request.param(param);
            if (value != null) {
                extraParamsMap.put(param, value);
            }
        }
        upgradeRequest.extraParams(extraParamsMap);
        Map<String, String> params = new HashMap<>();
        params.put(BulkByScrollTask.Status.INCLUDE_CREATED, Boolean.toString(true));
        params.put(BulkByScrollTask.Status.INCLUDE_UPDATED, Boolean.toString(true));

        return channel -> client.execute(IndexUpgradeAction.INSTANCE, upgradeRequest,
                new RestBuilderListener<BulkByScrollResponse>(channel) {

                    @Override
                    public RestResponse buildResponse(BulkByScrollResponse response, XContentBuilder builder) throws Exception {
                        builder.startObject();
                        response.toXContent(builder, new ToXContent.DelegatingMapParams(params, channel.request()));
                        builder.endObject();
                        return new BytesRestResponse(getStatus(response), builder);
                    }

                    private RestStatus getStatus(BulkByScrollResponse response) {
                        /*
                         * Return the highest numbered rest status under the assumption that higher numbered statuses are "more error"
                         * and thus more interesting to the user.
                         */
                        RestStatus status = RestStatus.OK;
                        if (response.isTimedOut()) {
                            status = RestStatus.REQUEST_TIMEOUT;
                        }
                        for (BulkItemResponse.Failure failure : response.getBulkFailures()) {
                            if (failure.getStatus().getStatus() > status.getStatus()) {
                                status = failure.getStatus();
                            }
                        }
                        for (ScrollableHitSource.SearchFailure failure : response.getSearchFailures()) {
                            RestStatus failureStatus = ExceptionsHelper.status(failure.getReason());
                            if (failureStatus.getStatus() > status.getStatus()) {
                                status = failureStatus;
                            }
                        }
                        return status;
                    }

                });
    }
}

