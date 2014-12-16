/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport;

import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

public class SecuredClientTransportService extends TransportService {

    private final ClientTransportFilter clientFilter;

    @Inject
    public SecuredClientTransportService(Settings settings, Transport transport, ThreadPool threadPool, ClientTransportFilter clientFilter) {
        super(settings, transport, threadPool);
        this.clientFilter = clientFilter;
    }


    @Override
    public <T extends TransportResponse> void sendRequest(DiscoveryNode node, String action, TransportRequest request, TransportRequestOptions options, TransportResponseHandler<T> handler) {
        try {
            clientFilter.outbound(action, request);
            super.sendRequest(node, action, request, options, handler);
        } catch (Throwable t) {
            handler.handleException(new TransportException("failed sending request", t));
        }
    }

}
