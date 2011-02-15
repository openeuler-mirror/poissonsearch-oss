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

package org.elasticsearch.rest.action.admin.cluster.node.shutdown;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.node.shutdown.NodesShutdownRequest;
import org.elasticsearch.action.admin.cluster.node.shutdown.NodesShutdownResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.rest.*;
import org.elasticsearch.rest.action.support.RestActions;

import java.io.IOException;

import static org.elasticsearch.rest.action.support.RestXContentBuilder.*;

/**
 * @author kimchy (shay.banon)
 */
public class RestNodesShutdownAction extends BaseRestHandler {

    @Inject public RestNodesShutdownAction(Settings settings, Client client, RestController controller) {
        super(settings, client);

        controller.registerHandler(RestRequest.Method.POST, "/_shutdown", this);
        controller.registerHandler(RestRequest.Method.POST, "/_cluster/nodes/_shutdown", this);
        controller.registerHandler(RestRequest.Method.POST, "/_cluster/nodes/{nodeId}/_shutdown", this);
    }

    @Override public void handleRequest(final RestRequest request, final RestChannel channel) {
        String[] nodesIds = RestActions.splitNodes(request.param("nodeId"));
        NodesShutdownRequest nodesShutdownRequest = new NodesShutdownRequest(nodesIds);
        nodesShutdownRequest.listenerThreaded(false);
        nodesShutdownRequest.delay(request.paramAsTime("delay", nodesShutdownRequest.delay()));
        client.admin().cluster().nodesShutdown(nodesShutdownRequest, new ActionListener<NodesShutdownResponse>() {
            @Override public void onResponse(NodesShutdownResponse response) {
                try {
                    XContentBuilder builder = restContentBuilder(request);
                    builder.startObject();
                    builder.field("cluster_name", response.clusterName().value());

                    builder.startObject("nodes");
                    for (DiscoveryNode node : response.nodes()) {
                        builder.startObject(node.id());
                        builder.field("name", node.name());
                        builder.endObject();
                    }
                    builder.endObject();

                    builder.endObject();
                    channel.sendResponse(new XContentRestResponse(request, RestStatus.OK, builder));
                } catch (Exception e) {
                    onFailure(e);
                }
            }

            @Override public void onFailure(Throwable e) {
                try {
                    channel.sendResponse(new XContentThrowableRestResponse(request, e));
                } catch (IOException e1) {
                    logger.error("Failed to send failure response", e1);
                }
            }
        });
    }
}