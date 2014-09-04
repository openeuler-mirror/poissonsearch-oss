/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest;

import com.google.common.collect.Sets;
import org.elasticsearch.action.*;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.FilterClient;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;

import java.util.Collections;
import java.util.Set;

/**
 * Base handler for REST requests
 */
public abstract class BaseRestHandler extends AbstractComponent implements RestHandler {

    private static Set<String> usefulHeaders = Sets.newCopyOnWriteArraySet();

    /**
     * Controls which REST headers get copied over from a {@link org.elasticsearch.rest.RestRequest} to
     * its corresponding {@link org.elasticsearch.transport.TransportRequest}(s).
     *
     * By default no headers get copied but it is possible to extend this behaviour via plugins by calling this method.
     */
    public static void addUsefulHeaders(String... headers) {
        Collections.addAll(usefulHeaders, headers);
    }

    static Set<String> usefulHeaders() {
        return usefulHeaders;
    }

    private final Client client;

    protected BaseRestHandler(Settings settings, Client client) {
        super(settings);
        this.client = client;
    }

    @Override
    public final void handleRequest(RestRequest request, RestChannel channel) throws Exception {
        handleRequest(request, channel, usefulHeaders.size() == 0 ? client : new HeadersCopyClient(client, request, usefulHeaders));
    }

    protected abstract void handleRequest(RestRequest request, RestChannel channel, Client client) throws Exception;

    static final class HeadersCopyClient extends FilterClient {

        private final RestRequest restRequest;
        private final Set<String> usefulHeaders;
        private final IndicesAdmin indicesAdmin;
        private final ClusterAdmin clusterAdmin;

        HeadersCopyClient(Client in, RestRequest restRequest, Set<String> usefulHeaders) {
            super(in);
            this.restRequest = restRequest;
            this.usefulHeaders = usefulHeaders;
            this.indicesAdmin = new IndicesAdmin(in.admin().indices());
            this.clusterAdmin = new ClusterAdmin(in.admin().cluster());
        }

        private void copyHeaders(ActionRequest request) {
            for (String usefulHeader : usefulHeaders) {
                String headerValue = restRequest.header(usefulHeader);
                if (headerValue != null) {
                    request.putHeader(usefulHeader, headerValue);
                }
            }
        }

        @Override
        public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder, Client>> ActionFuture<Response> execute(Action<Request, Response, RequestBuilder, Client> action, Request request) {
            copyHeaders(request);
            return super.execute(action, request);
        }

        @Override
        public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder, Client>> void execute(Action<Request, Response, RequestBuilder, Client> action, Request request, ActionListener<Response> listener) {
            copyHeaders(request);
            super.execute(action, request, listener);
        }

        @Override
        public ClusterAdminClient cluster() {
            return clusterAdmin;
        }

        @Override
        public IndicesAdminClient indices() {
            return indicesAdmin;
        }

        private final class ClusterAdmin extends FilterClient.ClusterAdmin {
            private ClusterAdmin(ClusterAdminClient in) {
                super(in);
            }

            @Override
            public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder, ClusterAdminClient>> ActionFuture<Response> execute(Action<Request, Response, RequestBuilder, ClusterAdminClient> action, Request request) {
                copyHeaders(request);
                return super.execute(action, request);
            }

            @Override
            public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder, ClusterAdminClient>> void execute(Action<Request, Response, RequestBuilder, ClusterAdminClient> action, Request request, ActionListener<Response> listener) {
                copyHeaders(request);
                super.execute(action, request, listener);
            }
        }

        private final class IndicesAdmin extends FilterClient.IndicesAdmin {
            private IndicesAdmin(IndicesAdminClient in) {
                super(in);
            }

            @Override
            public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder, IndicesAdminClient>> ActionFuture<Response> execute(Action<Request, Response, RequestBuilder, IndicesAdminClient> action, Request request) {
                copyHeaders(request);
                return super.execute(action, request);
            }

            @Override
            public <Request extends ActionRequest, Response extends ActionResponse, RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder, IndicesAdminClient>> void execute(Action<Request, Response, RequestBuilder, IndicesAdminClient> action, Request request, ActionListener<Response> listener) {
                copyHeaders(request);
                super.execute(action, request, listener);
            }
        }
    }
}