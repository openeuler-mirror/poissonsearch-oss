/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action.authz.cache;

import org.elasticsearch.action.support.nodes.NodesOperationRequestBuilder;
import org.elasticsearch.client.ElasticsearchClient;

/**
 * Request builder for the {@link ClearRolesCacheRequest}
 */
public class ClearRolesCacheRequestBuilder extends NodesOperationRequestBuilder<ClearRolesCacheRequest, ClearRolesCacheResponse, ClearRolesCacheRequestBuilder> {

    public ClearRolesCacheRequestBuilder(ElasticsearchClient client) {
        this(client, ClearRolesCacheAction.INSTANCE, new ClearRolesCacheRequest());
    }

    public ClearRolesCacheRequestBuilder(ElasticsearchClient client, ClearRolesCacheAction action, ClearRolesCacheRequest request) {
        super(client, action, request);
    }

    /**
     * Set the roles to be cleared
     * @param roles the names of the roles that should be cleared
     * @return the builder instance
     */
    public ClearRolesCacheRequestBuilder roles(String... roles) {
        request.roles(roles);
        return this;
    }
}
