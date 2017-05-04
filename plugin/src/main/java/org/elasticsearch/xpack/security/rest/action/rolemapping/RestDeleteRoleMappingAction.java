/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.rest.action.rolemapping;

import java.io.IOException;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestBuilderListener;
import org.elasticsearch.xpack.security.action.rolemapping.DeleteRoleMappingResponse;
import org.elasticsearch.xpack.security.client.SecurityClient;

import static org.elasticsearch.rest.RestRequest.Method.DELETE;

/**
 * Rest endpoint to delete a role-mapping from the {@link org.elasticsearch.xpack.security.authc.support.mapper.NativeRoleMappingStore}
 */
public class RestDeleteRoleMappingAction extends BaseRestHandler {

    public RestDeleteRoleMappingAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(DELETE, "/_xpack/security/role_mapping/{name}", this);
    }

    @Override
    public RestChannelConsumer prepareRequest(RestRequest request, NodeClient client)
            throws IOException {
        final String name = request.param("name");
        final String refresh = request.param("refresh");

        return channel -> new SecurityClient(client).prepareDeleteRoleMapping(name)
                .setRefreshPolicy(refresh)
                .execute(new RestBuilderListener<DeleteRoleMappingResponse>(channel) {
                    @Override
                    public RestResponse buildResponse(DeleteRoleMappingResponse response, XContentBuilder builder) throws Exception {
                        return new BytesRestResponse(response.isFound() ? RestStatus.OK : RestStatus.NOT_FOUND,
                                builder.startObject().field("found", response.isFound()).endObject());
                    }
                });
    }
}
