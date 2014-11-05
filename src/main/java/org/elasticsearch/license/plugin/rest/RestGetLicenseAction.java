/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.rest;

import org.elasticsearch.client.Client;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.license.core.ESLicenses;
import org.elasticsearch.license.plugin.action.get.GetLicenseAction;
import org.elasticsearch.license.plugin.action.get.GetLicenseRequest;
import org.elasticsearch.license.plugin.action.get.GetLicenseResponse;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestBuilderListener;

import java.util.Map;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestStatus.OK;

public class RestGetLicenseAction extends BaseRestHandler {

    @Inject
    public RestGetLicenseAction(Settings settings, RestController controller, Client client) {
        super(settings, controller, client);
        controller.registerHandler(GET, "/_licenses", this);
    }

    /**
     * Output Format:
     * {
     *   "licenses" : [
     *     {
     *       "uid" : ...,
     *       "type" : ...,
     *       "subscription_type" :...,
     *       "issued_to" : ... (cluster name if one-time trial license, else value from signed license),
     *       "issue_date" : YY-MM-DD (date string in UTC),
     *       "expiry_date" : YY-MM-DD (date string in UTC),
     *       "feature" : ...,
     *       "max_nodes" : ...
     *     },
     * {...}
     *   ]
     * }p
     * <p/>
     * There will be only one license displayed per feature, the selected license will have the latest expiry_date
     * out of all other licenses for the feature.
     * <p/>
     * The licenses are sorted by latest issue_date
     */

    @Override
    public void handleRequest(final RestRequest request, final RestChannel channel, final Client client) {
        final Map<String, String> overrideParams = ImmutableMap.of(ESLicenses.OMIT_SIGNATURE, "true");
        final ToXContent.Params params = new ToXContent.DelegatingMapParams(overrideParams, request);
        GetLicenseRequest getLicenseRequest = new GetLicenseRequest();
        getLicenseRequest.local(request.paramAsBoolean("local", getLicenseRequest.local()));
        client.admin().cluster().execute(GetLicenseAction.INSTANCE, getLicenseRequest, new RestBuilderListener<GetLicenseResponse>(channel) {
            @Override
            public RestResponse buildResponse(GetLicenseResponse response, XContentBuilder builder) throws Exception {
                ESLicenses.toXContent(response.licenses(), builder, params);
                return new BytesRestResponse(OK, builder);
            }
        });
    }

}
