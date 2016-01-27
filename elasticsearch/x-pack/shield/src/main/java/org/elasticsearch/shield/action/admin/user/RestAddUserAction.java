/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action.admin.user;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.support.RestBuilderListener;
import org.elasticsearch.shield.client.ShieldClient;

import java.io.IOException;

/**
 * Rest endpoint to add a User to the shield index
 */
public class RestAddUserAction extends BaseRestHandler {

    @Inject
    public RestAddUserAction(Settings settings, RestController controller, Client client) {
        super(settings, client);
        controller.registerHandler(RestRequest.Method.POST, "/_shield/user/{username}", this);
        controller.registerHandler(RestRequest.Method.PUT, "/_shield/user/{username}", this);
    }

    @Override
    protected void handleRequest(RestRequest request, final RestChannel channel, Client client) throws Exception {
        AddUserRequest addUserReq = new AddUserRequest();
        addUserReq.username(request.param("username"));
        addUserReq.source(request.content());

        new ShieldClient(client).addUser(addUserReq, new RestBuilderListener<AddUserResponse>(channel) {
            @Override
            public RestResponse buildResponse(AddUserResponse addUserResponse, XContentBuilder builder) throws Exception {
                return new BytesRestResponse(RestStatus.OK,
                        builder.startObject()
                                .field("user", (ToXContent) addUserResponse)
                                .endObject());
            }
        });
    }
}
