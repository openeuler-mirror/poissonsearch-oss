/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.transport.actions.delete;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.indexedscripts.delete.DeleteIndexedScriptAction;
import org.elasticsearch.action.indexedscripts.delete.DeleteIndexedScriptRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.DelegatingActionListener;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.master.TransportMasterNodeOperationAction;
import org.elasticsearch.alerts.AlertManager;
import org.elasticsearch.alerts.AlertsStore;
import org.elasticsearch.alerts.actions.AlertActionManager;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 * Performs the delete operation.
 */
public class TransportDeleteAlertAction extends TransportMasterNodeOperationAction<DeleteAlertRequest,  DeleteAlertResponse> {

    private final AlertManager alertManager;

    @Inject
    public TransportDeleteAlertAction(Settings settings, String actionName, TransportService transportService,
                                      ClusterService clusterService, ThreadPool threadPool, ActionFilters actionFilters,
                                      AlertManager alertManager) {
        super(settings, actionName, transportService, clusterService, threadPool, actionFilters);
        this.alertManager = alertManager;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.MANAGEMENT;
    }

    @Override
    protected DeleteAlertRequest newRequest() {
        return new DeleteAlertRequest();
    }

    @Override
    protected DeleteAlertResponse newResponse() {
        return new DeleteAlertResponse();
    }

    @Override
    protected void masterOperation(DeleteAlertRequest request, ClusterState state, ActionListener<DeleteAlertResponse> listener) throws ElasticsearchException {
        try {
            listener.onResponse(new DeleteAlertResponse(alertManager.deleteAlert(request.alertName())));
        } catch (Exception e) {
            listener.onFailure(e);
        }
    }

    @Override
    protected ClusterBlockException checkBlock(DeleteAlertRequest request, ClusterState state) {
        if (!alertManager.isStarted()) {
            return new ClusterBlockException(null);
        }
        return state.blocks().indicesBlockedException(ClusterBlockLevel.WRITE, new String[]{AlertsStore.ALERT_INDEX, AlertActionManager.ALERT_HISTORY_INDEX});
    }


}
