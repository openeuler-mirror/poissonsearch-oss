/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action.user;

import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.client.ElasticsearchClient;

public class DeleteUserRequestBuilder extends ActionRequestBuilder<DeleteUserRequest, DeleteUserResponse, DeleteUserRequestBuilder> {

    public DeleteUserRequestBuilder(ElasticsearchClient client) {
        this(client, DeleteUserAction.INSTANCE);
    }

    public DeleteUserRequestBuilder(ElasticsearchClient client, DeleteUserAction action) {
        super(client, action, new DeleteUserRequest());
    }

    public DeleteUserRequestBuilder username(String username) {
        request.username(username);
        return this;
    }

    public DeleteUserRequestBuilder refresh(boolean refresh) {
        request.refresh(refresh);
        return this;
    }
}
