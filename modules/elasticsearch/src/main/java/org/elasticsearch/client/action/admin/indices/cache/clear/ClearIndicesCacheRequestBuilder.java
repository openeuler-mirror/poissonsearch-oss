/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.client.action.admin.indices.cache.clear;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.admin.indices.cache.clear.ClearIndicesCacheRequest;
import org.elasticsearch.action.admin.indices.cache.clear.ClearIndicesCacheResponse;
import org.elasticsearch.action.support.PlainListenableActionFuture;
import org.elasticsearch.action.support.broadcast.BroadcastOperationThreading;
import org.elasticsearch.client.internal.InternalIndicesAdminClient;

/**
 * @author kimchy (shay.banon)
 */
public class ClearIndicesCacheRequestBuilder {

    private final InternalIndicesAdminClient indicesClient;

    private final ClearIndicesCacheRequest request;

    public ClearIndicesCacheRequestBuilder(InternalIndicesAdminClient indicesClient) {
        this.indicesClient = indicesClient;
        this.request = new ClearIndicesCacheRequest();
    }

    public ClearIndicesCacheRequestBuilder setIndices(String... indices) {
        request.indices(indices);
        return this;
    }

    /**
     * Should the filter cache be cleared or not. Defaults to <tt>true</tt>.
     */
    public ClearIndicesCacheRequestBuilder setFilterCache(boolean filterCache) {
        request.filterCache(filterCache);
        return this;
    }

    /**
     * Should the listener be called on a separate thread if needed.
     */
    public ClearIndicesCacheRequestBuilder setListenerThreaded(boolean threadedListener) {
        request.listenerThreaded(threadedListener);
        return this;
    }

    /**
     * Controls the operation threading model.
     */
    public ClearIndicesCacheRequestBuilder setOperationThreading(BroadcastOperationThreading operationThreading) {
        request.operationThreading(operationThreading);
        return this;
    }

    /**
     * Executes the operation asynchronously and returns a future.
     */
    public ListenableActionFuture<ClearIndicesCacheResponse> execute() {
        PlainListenableActionFuture<ClearIndicesCacheResponse> future = new PlainListenableActionFuture<ClearIndicesCacheResponse>(request.listenerThreaded(), indicesClient.threadPool());
        indicesClient.clearCache(request, future);
        return future;
    }

    /**
     * Executes the operation asynchronously with the provided listener.
     */
    public void execute(ActionListener<ClearIndicesCacheResponse> listener) {
        indicesClient.clearCache(request, listener);
    }
}
