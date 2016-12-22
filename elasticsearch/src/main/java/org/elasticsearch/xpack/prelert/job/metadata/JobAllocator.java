/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.metadata;

import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;

/**
 * Runs only on the elected master node and decides to what nodes jobs should be allocated
 */
public class JobAllocator extends AbstractComponent implements ClusterStateListener {

    private final ThreadPool threadPool;
    private final ClusterService clusterService;

    public JobAllocator(Settings settings, ClusterService clusterService, ThreadPool threadPool) {
        super(settings);
        this.threadPool = threadPool;
        this.clusterService = clusterService;
        clusterService.addListener(this);
    }

    ClusterState assignJobsToNodes(ClusterState current) {
        if (shouldAllocate(current) == false) {
            // returning same instance, so no cluster state update is performed
            return current;
        }

        DiscoveryNodes nodes = current.getNodes();
        if (nodes.getSize() != 1) {
            throw new IllegalStateException("Current prelert doesn't support multiple nodes");
        }

        // NORELEASE: Assumes prelert always runs on a single node:
        PrelertMetadata prelertMetadata = current.getMetaData().custom(PrelertMetadata.TYPE);
        PrelertMetadata.Builder builder = new PrelertMetadata.Builder(prelertMetadata);
        DiscoveryNode prelertNode = nodes.getMasterNode(); // prelert is now always master node

        for (String jobId : prelertMetadata.getAllocations().keySet()) {
            builder.assignToNode(jobId, prelertNode.getId());
        }

        return ClusterState.builder(current)
                .metaData(MetaData.builder(current.metaData()).putCustom(PrelertMetadata.TYPE, builder.build()))
                .build();
    }

    boolean shouldAllocate(ClusterState current) {
        PrelertMetadata prelertMetadata = current.getMetaData().custom(PrelertMetadata.TYPE);
        if (prelertMetadata == null) {
            return false;
        }

        for (Allocation allocation : prelertMetadata.getAllocations().values()) {
            if (allocation.getNodeId() == null) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.localNodeMaster()) {
            if (shouldAllocate(event.state())) {
                threadPool.executor(ThreadPool.Names.GENERIC).execute(() -> {
                    clusterService.submitStateUpdateTask("allocate_jobs", new ClusterStateUpdateTask() {
                        @Override
                        public ClusterState execute(ClusterState currentState) throws Exception {
                            return assignJobsToNodes(currentState);
                        }

                        @Override
                        public void onFailure(String source, Exception e) {
                            logger.error("failed to allocate jobs", e);
                        }
                    });
                });
            }
        }
    }
}
