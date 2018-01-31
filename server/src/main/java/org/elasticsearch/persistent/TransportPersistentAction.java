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

package org.elasticsearch.persistent;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportResponse.Empty;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.persistent.PersistentTasksInProgress.Assignment;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * An action that can survive restart of requesting or executing node.
 * These actions are using cluster state rather than only transport service to send requests and responses.
 */
public abstract class TransportPersistentAction<Request extends PersistentActionRequest>
        extends HandledTransportAction<Request, PersistentActionResponse> {

    private final String executor;
    private final PersistentActionService persistentActionService;

    protected TransportPersistentAction(Settings settings, String actionName, boolean canTripCircuitBreaker, ThreadPool threadPool,
                                        TransportService transportService, PersistentActionService persistentActionService,
                                        PersistentActionRegistry persistentActionRegistry,
                                        ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                        Supplier<Request> requestSupplier, String executor) {
        super(settings, actionName, canTripCircuitBreaker, threadPool, transportService, actionFilters, indexNameExpressionResolver,
                requestSupplier);
        this.executor = executor;
        this.persistentActionService = persistentActionService;
        persistentActionRegistry.registerPersistentAction(actionName, this);
    }

    public static final Assignment NO_NODE_FOUND = new Assignment(null, "no appropriate nodes found for the assignment");

    /**
     * Returns the node id where the request has to be executed,
     * <p>
     * The default implementation returns the least loaded data node
     */
    public Assignment getAssignment(Request request, ClusterState clusterState) {
        DiscoveryNode discoveryNode = selectLeastLoadedNode(clusterState, DiscoveryNode::isDataNode);
        if (discoveryNode == null) {
            return NO_NODE_FOUND;
        } else {
            return new Assignment(discoveryNode.getId(), "");
        }
    }


    /**
     * Finds the least loaded node that satisfies the selector criteria
     */
    protected DiscoveryNode selectLeastLoadedNode(ClusterState clusterState, Predicate<DiscoveryNode> selector) {
        long minLoad = Long.MAX_VALUE;
        DiscoveryNode minLoadedNode = null;
        PersistentTasksInProgress persistentTasksInProgress = clusterState.getMetaData().custom(PersistentTasksInProgress.TYPE);
        for (DiscoveryNode node : clusterState.getNodes()) {
            if (selector.test(node)) {
                if (persistentTasksInProgress == null) {
                    // We don't have any task running yet, pick the first available node
                    return node;
                }
                long numberOfTasks = persistentTasksInProgress.getNumberOfTasksOnNode(node.getId(), actionName);
                if (minLoad > numberOfTasks) {
                    minLoad = numberOfTasks;
                    minLoadedNode = node;
                }
            }
        }
        return minLoadedNode;
    }

    /**
     * Checks the current cluster state for compatibility with the request
     * <p>
     * Throws an exception if the supplied request cannot be executed on the cluster in the current state.
     */
    public void validate(Request request, ClusterState clusterState) {

    }

    @Override
    protected void doExecute(Request request, ActionListener<PersistentActionResponse> listener) {
        persistentActionService.sendRequest(actionName, request, listener);
    }

    /**
     * Updates the persistent task status in the cluster state.
     * <p>
     * The status can be used to store the current progress of the task or provide an insight for the
     * task allocator about the state of the currently running tasks.
     */
    protected void updatePersistentTaskStatus(PersistentTask task, Task.Status status, ActionListener<Empty> listener) {
        persistentActionService.updateStatus(task.getPersistentTaskId(), status,
                new ActionListener<UpdatePersistentTaskStatusAction.Response>() {
                    @Override
                    public void onResponse(UpdatePersistentTaskStatusAction.Response response) {
                        listener.onResponse(Empty.INSTANCE);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        listener.onFailure(e);
                    }
                });
    }

    /**
     * This operation will be executed on the executor node.
     * <p>
     * If nodeOperation throws an exception or triggers listener.onFailure() method, the task will be restarted,
     * possibly on a different node. If listener.onResponse() is called, the task is considered to be successfully
     * completed and will be removed from the cluster state and not restarted.
     */
    protected abstract void nodeOperation(PersistentTask task, Request request, ActionListener<Empty> listener);

    public String getExecutor() {
        return executor;
    }
}
