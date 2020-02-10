/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.rollup.rest;

import org.apache.logging.log4j.LogManager;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.logging.DeprecationLogger;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.core.rollup.action.PutRollupJobAction;

import java.io.IOException;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.elasticsearch.rest.RestRequest.Method.PUT;

public class RestPutRollupJobAction extends BaseRestHandler {

    private static final DeprecationLogger deprecationLogger = new DeprecationLogger(LogManager.getLogger(RestPutRollupJobAction.class));

    @Override
    public List<Route> routes() {
        return emptyList();
    }

    @Override
    public List<ReplacedRoute> replacedRoutes() {
        return singletonList(new ReplacedRoute(PUT, "/_rollup/job/{id}", PUT, "/_xpack/rollup/job/{id}", deprecationLogger));
    }

    @Override
    protected RestChannelConsumer prepareRequest(final RestRequest request, final NodeClient client) throws IOException {
        final String id = request.param("id");
        final PutRollupJobAction.Request putRollupJobRequest = PutRollupJobAction.Request.fromXContent(request.contentParser(), id);
        return channel -> client.execute(PutRollupJobAction.INSTANCE, putRollupJobRequest, new RestToXContentListener<>(channel));
    }

    @Override
    public String getName() {
        return "put_rollup_job";
    }

}
