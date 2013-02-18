/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.action.admin.cluster.node.hotthreads;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.nodes.NodesOperationRequestBuilder;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.internal.InternalClusterAdminClient;
import org.elasticsearch.common.unit.TimeValue;

/**
 */
public class NodesHotThreadsRequestBuilder extends NodesOperationRequestBuilder<NodesHotThreadsRequest, NodesHotThreadsResponse, NodesHotThreadsRequestBuilder> {

    public NodesHotThreadsRequestBuilder(ClusterAdminClient clusterClient) {
        super((InternalClusterAdminClient) clusterClient, new NodesHotThreadsRequest());
    }

    public NodesHotThreadsRequestBuilder setThreads(int threads) {
        request.setThreads(threads);
        return this;
    }

    public NodesHotThreadsRequestBuilder setType(String type) {
        request.setType(type);
        return this;
    }

    public NodesHotThreadsRequestBuilder setInterval(TimeValue interval) {
        request.setInterval(interval);
        return this;
    }

    @Override
    protected void doExecute(ActionListener<NodesHotThreadsResponse> listener) {
        ((ClusterAdminClient) client).nodesHotThreads(request, listener);
    }
}
