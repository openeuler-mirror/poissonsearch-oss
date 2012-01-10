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

package org.elasticsearch.client.action.admin.cluster.node.info;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.action.admin.cluster.support.BaseClusterRequestBuilder;

/**
 *
 */
public class NodesInfoRequestBuilder extends BaseClusterRequestBuilder<NodesInfoRequest, NodesInfoResponse> {

    public NodesInfoRequestBuilder(ClusterAdminClient clusterClient) {
        super(clusterClient, new NodesInfoRequest());
    }

    public NodesInfoRequestBuilder setNodesIds(String... nodesIds) {
        request.nodesIds(nodesIds);
        return this;
    }

    /**
     * Clears all info flags.
     */
    public NodesInfoRequestBuilder clear() {
        request.clear();
        return this;
    }

    /**
     * Should the node settings be returned.
     */
    public NodesInfoRequestBuilder setSettings(boolean settings) {
        request.settings(settings);
        return this;
    }

    /**
     * Should the node OS info be returned.
     */
    public NodesInfoRequestBuilder setOs(boolean os) {
        request.os(os);
        return this;
    }

    /**
     * Should the node OS process be returned.
     */
    public NodesInfoRequestBuilder setProcess(boolean process) {
        request.process(process);
        return this;
    }

    /**
     * Should the node JVM info be returned.
     */
    public NodesInfoRequestBuilder setJvm(boolean jvm) {
        request.jvm(jvm);
        return this;
    }

    /**
     * Should the node thread pool info be returned.
     */
    public NodesInfoRequestBuilder setThreadPool(boolean threadPool) {
        request.threadPool(threadPool);
        return this;
    }

    /**
     * Should the node Network info be returned.
     */
    public NodesInfoRequestBuilder setNetwork(boolean network) {
        request.network(network);
        return this;
    }

    /**
     * Should the node Transport info be returned.
     */
    public NodesInfoRequestBuilder setTransport(boolean transport) {
        request.transport(transport);
        return this;
    }

    /**
     * Should the node HTTP info be returned.
     */
    public NodesInfoRequestBuilder setHttp(boolean http) {
        request.http(http);
        return this;
    }


    @Override
    protected void doExecute(ActionListener<NodesInfoResponse> listener) {
        client.nodesInfo(request, listener);
    }
}
