/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action.interceptor;

import org.elasticsearch.action.CompositeIndicesRequest;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.authz.accesscontrol.IndicesAccessControl;
import org.elasticsearch.shield.authz.InternalAuthorizationService;
import org.elasticsearch.transport.TransportRequest;

import java.util.Collections;
import java.util.List;

/**
 * Base class for interceptors that disables features when field level security is configured for indices a request
 * is going to execute on.
 */
public abstract class FieldSecurityRequestInterceptor<Request> extends AbstractComponent implements RequestInterceptor<Request> {

    public FieldSecurityRequestInterceptor(Settings settings) {
        super(settings);
    }

    public void intercept(Request request, User user) {
        List<? extends IndicesRequest> indicesRequests;
        if (request instanceof CompositeIndicesRequest) {
            indicesRequests = ((CompositeIndicesRequest) request).subRequests();
        } else if (request instanceof IndicesRequest) {
            indicesRequests = Collections.singletonList((IndicesRequest) request);
        } else {
            return;
        }
        IndicesAccessControl indicesAccessControl = ((TransportRequest) request).getFromContext(InternalAuthorizationService.INDICES_PERMISSIONS_KEY);
        for (IndicesRequest indicesRequest : indicesRequests) {
            for (String index : indicesRequest.indices()) {
                IndicesAccessControl.IndexAccessControl indexAccessControl = indicesAccessControl.getIndexPermissions(index);
                if (indexAccessControl != null && indexAccessControl.getFields() != null) {
                    logger.debug("intercepted request for index [{}] with field level security enabled, disabling features", index);
                    disableFeatures(request);
                    return;
                } else {
                    logger.trace("intercepted request for index [{}] with field level security not enabled, doing nothing", index);
                }
            }
        }
    }

    protected abstract void disableFeatures(Request request);

}
