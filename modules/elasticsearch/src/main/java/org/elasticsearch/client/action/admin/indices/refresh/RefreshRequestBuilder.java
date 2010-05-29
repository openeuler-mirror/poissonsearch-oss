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

package org.elasticsearch.client.action.admin.indices.refresh;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.support.PlainListenableActionFuture;
import org.elasticsearch.action.support.broadcast.BroadcastOperationThreading;
import org.elasticsearch.client.internal.InternalIndicesAdminClient;

/**
 * A refresh request making all operations performed since the last refresh available for search. The (near) real-time
 * capabilities depends on the index engine used. For example, the robin one requires refresh to be called, but by
 * default a refresh is scheduled periodically.
 *
 * @author kimchy (shay.banon)
 */
public class RefreshRequestBuilder {

    private final InternalIndicesAdminClient indicesClient;

    private final RefreshRequest request;

    public RefreshRequestBuilder(InternalIndicesAdminClient indicesClient) {
        this.indicesClient = indicesClient;
        this.request = new RefreshRequest();
    }

    public RefreshRequestBuilder setIndices(String... indices) {
        request.indices(indices);
        return this;
    }

    public RefreshRequestBuilder setWaitForOperations(boolean waitForOperations) {
        request.waitForOperations(waitForOperations);
        return this;
    }

    /**
     * Should the listener be called on a separate thread if needed.
     */
    public RefreshRequestBuilder setListenerThreaded(boolean threadedListener) {
        request.listenerThreaded(threadedListener);
        return this;
    }

    /**
     * Controls the operation threading model.
     */
    public RefreshRequestBuilder setOperationThreading(BroadcastOperationThreading operationThreading) {
        request.operationThreading(operationThreading);
        return this;
    }

    /**
     * Executes the operation asynchronously and returns a future.
     */
    public ListenableActionFuture<RefreshResponse> execute() {
        PlainListenableActionFuture<RefreshResponse> future = new PlainListenableActionFuture<RefreshResponse>(request.listenerThreaded(), indicesClient.threadPool());
        indicesClient.refresh(request, future);
        return future;
    }

    /**
     * Executes the operation asynchronously with the provided listener.
     */
    public void execute(ActionListener<RefreshResponse> listener) {
        indicesClient.refresh(request, listener);
    }
}
