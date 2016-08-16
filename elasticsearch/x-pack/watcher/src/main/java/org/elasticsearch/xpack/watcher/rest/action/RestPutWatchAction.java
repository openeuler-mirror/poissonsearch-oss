/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.rest.action;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.elasticsearch.xpack.watcher.client.WatcherClient;
import org.elasticsearch.xpack.watcher.rest.WatcherRestHandler;
import org.elasticsearch.xpack.watcher.transport.actions.put.PutWatchRequest;
import org.elasticsearch.xpack.watcher.transport.actions.put.PutWatchResponse;

import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestRequest.Method.PUT;
import static org.elasticsearch.rest.RestStatus.CREATED;
import static org.elasticsearch.rest.RestStatus.OK;

/**
 */
public class RestPutWatchAction extends WatcherRestHandler {

    @Inject
    public RestPutWatchAction(Settings settings, RestController controller) {
        super(settings);

        // @deprecated Remove deprecations in 6.0
        controller.registerWithDeprecatedHandler(POST, URI_BASE + "/watch/{id}", this,
                                                 POST, "/_watcher/watch/{id}", deprecationLogger);
        controller.registerWithDeprecatedHandler(PUT, URI_BASE + "/watch/{id}", this,
                                                 PUT, "/_watcher/watch/{id}", deprecationLogger);
    }

    @Override
    protected void handleRequest(final RestRequest request, RestChannel channel, WatcherClient client) throws Exception {
        PutWatchRequest putWatchRequest = new PutWatchRequest(request.param("id"), request.content());
        putWatchRequest.masterNodeTimeout(request.paramAsTime("master_timeout", putWatchRequest.masterNodeTimeout()));
        putWatchRequest.setActive(request.paramAsBoolean("active", putWatchRequest.isActive()));
        client.putWatch(putWatchRequest, new RestBuilderListener<PutWatchResponse>(channel) {
            @Override
            public RestResponse buildResponse(PutWatchResponse response, XContentBuilder builder) throws Exception {
                builder.startObject()
                        .field("_id", response.getId())
                        .field("_version", response.getVersion())
                        .field("created", response.isCreated())
                        .endObject();
                RestStatus status = response.isCreated() ? CREATED : OK;
                return new BytesRestResponse(status, builder);
            }
        });
    }
}
