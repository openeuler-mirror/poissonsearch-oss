/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.rest.action.role;

import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.action.RestActions.NodesResponseRestListener;
import org.elasticsearch.xpack.security.action.role.ClearRolesCacheRequest;
import org.elasticsearch.xpack.security.client.SecurityClient;
import org.elasticsearch.xpack.security.rest.action.SecurityBaseRestHandler;

import java.io.IOException;

import static org.elasticsearch.rest.RestRequest.Method.POST;

public final class RestClearRolesCacheAction extends SecurityBaseRestHandler {

    public RestClearRolesCacheAction(Settings settings, RestController controller, XPackLicenseState licenseState) {
        super(settings, licenseState);
        controller.registerHandler(POST, "/_xpack/security/role/{name}/_clear_cache", this);
    }

    @Override
    public String getName() {
        return "xpack_security_clear_roles_cache_action";
    }

    @Override
    public RestChannelConsumer innerPrepareRequest(RestRequest request, NodeClient client) throws IOException {
        String[] roles = request.paramAsStringArrayOrEmptyIfAll("name");

        ClearRolesCacheRequest req = new ClearRolesCacheRequest().names(roles);

        return channel -> new SecurityClient(client).clearRolesCache(req, new NodesResponseRestListener<>(channel));
    }
}
