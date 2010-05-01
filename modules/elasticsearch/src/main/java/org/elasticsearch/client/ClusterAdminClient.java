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

package org.elasticsearch.client;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest;
import org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse;
import org.elasticsearch.action.admin.cluster.node.restart.NodesRestartRequest;
import org.elasticsearch.action.admin.cluster.node.restart.NodesRestartResponse;
import org.elasticsearch.action.admin.cluster.node.shutdown.NodesShutdownRequest;
import org.elasticsearch.action.admin.cluster.node.shutdown.NodesShutdownResponse;
import org.elasticsearch.action.admin.cluster.ping.broadcast.BroadcastPingRequest;
import org.elasticsearch.action.admin.cluster.ping.broadcast.BroadcastPingResponse;
import org.elasticsearch.action.admin.cluster.ping.replication.ReplicationPingRequest;
import org.elasticsearch.action.admin.cluster.ping.replication.ReplicationPingResponse;
import org.elasticsearch.action.admin.cluster.ping.single.SinglePingRequest;
import org.elasticsearch.action.admin.cluster.ping.single.SinglePingResponse;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse;

/**
 * Administrative actions/operations against indices.
 *
 * @author kimchy (Shay Banon)
 * @see AdminClient#cluster()
 */
public interface ClusterAdminClient {

    /**
     * The health of the cluster.
     *
     * @param request The cluster state request
     * @return The result future
     * @see Requests#clusterHealth(String...)
     */
    ActionFuture<ClusterHealthResponse> health(ClusterHealthRequest request);

    /**
     * The health of the cluster.
     *
     * @param request  The cluster state request
     * @param listener A listener to be notified with a result
     * @see Requests#clusterHealth(String...)
     */
    void health(ClusterHealthRequest request, ActionListener<ClusterHealthResponse> listener);

    /**
     * The state of the cluster.
     *
     * @param request The cluster state request.
     * @return The result future
     * @see Requests#clusterState()
     */
    ActionFuture<ClusterStateResponse> state(ClusterStateRequest request);

    /**
     * The state of the cluster.
     *
     * @param request  The cluster state request.
     * @param listener A listener to be notified with a result
     * @see Requests#clusterState()
     */
    void state(ClusterStateRequest request, ActionListener<ClusterStateResponse> listener);

    /**
     * Nodes info of the cluster.
     *
     * @param request The nodes info request
     * @return The result future
     * @see org.elasticsearch.client.Requests#nodesInfo(String...)
     */
    ActionFuture<NodesInfoResponse> nodesInfo(NodesInfoRequest request);

    /**
     * Nodes info of the cluster.
     *
     * @param request  The nodes info request
     * @param listener A listener to be notified with a result
     * @see org.elasticsearch.client.Requests#nodesShutdown(String...)
     */
    void nodesInfo(NodesInfoRequest request, ActionListener<NodesInfoResponse> listener);

    /**
     * Shutdown nodes in the cluster.
     *
     * @param request The nodes shutdown request
     * @return The result future
     * @see org.elasticsearch.client.Requests#nodesShutdown(String...)
     */
    ActionFuture<NodesShutdownResponse> nodesShutdown(NodesShutdownRequest request);

    /**
     * Shutdown nodes in the cluster.
     *
     * @param request  The nodes shutdown request
     * @param listener A listener to be notified with a result
     * @see org.elasticsearch.client.Requests#nodesShutdown(String...)
     */
    void nodesShutdown(NodesShutdownRequest request, ActionListener<NodesShutdownResponse> listener);

    /**
     * Restarts nodes in the cluster.
     *
     * @param request The nodes restart request
     * @return The result future
     * @see org.elasticsearch.client.Requests#nodesRestart(String...)
     */
    ActionFuture<NodesRestartResponse> nodesRestart(NodesRestartRequest request);

    /**
     * Restarts nodes in the cluster.
     *
     * @param request  The nodes restart request
     * @param listener A listener to be notified with a result
     * @see org.elasticsearch.client.Requests#nodesRestart(String...)
     */
    void nodesRestart(NodesRestartRequest request, ActionListener<NodesRestartResponse> listener);

    ActionFuture<SinglePingResponse> ping(SinglePingRequest request);

    void ping(SinglePingRequest request, ActionListener<SinglePingResponse> listener);

    ActionFuture<BroadcastPingResponse> ping(BroadcastPingRequest request);

    void ping(BroadcastPingRequest request, ActionListener<BroadcastPingResponse> listener);

    ActionFuture<ReplicationPingResponse> ping(ReplicationPingRequest request);

    void ping(ReplicationPingRequest request, ActionListener<ReplicationPingResponse> listener);
}
