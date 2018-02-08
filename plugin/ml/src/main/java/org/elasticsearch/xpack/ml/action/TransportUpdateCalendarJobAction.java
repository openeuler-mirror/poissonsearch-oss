/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ml.MLMetadataField;
import org.elasticsearch.xpack.core.ml.MlMetadata;
import org.elasticsearch.xpack.core.ml.action.PutCalendarAction;
import org.elasticsearch.xpack.core.ml.action.UpdateCalendarJobAction;
import org.elasticsearch.xpack.ml.job.JobManager;
import org.elasticsearch.xpack.ml.job.persistence.JobProvider;

import java.util.HashSet;
import java.util.Set;

public class TransportUpdateCalendarJobAction extends HandledTransportAction<UpdateCalendarJobAction.Request, PutCalendarAction.Response> {

    private final ClusterService clusterService;
    private final JobProvider jobProvider;
    private final JobManager jobManager;

    @Inject
    public TransportUpdateCalendarJobAction(Settings settings, ThreadPool threadPool,
                                            TransportService transportService, ActionFilters actionFilters,
                                            IndexNameExpressionResolver indexNameExpressionResolver,
                                            ClusterService clusterService, JobProvider jobProvider, JobManager jobManager) {
        super(settings, UpdateCalendarJobAction.NAME, threadPool, transportService, actionFilters,
                indexNameExpressionResolver, UpdateCalendarJobAction.Request::new);
        this.clusterService = clusterService;
        this.jobProvider = jobProvider;
        this.jobManager = jobManager;
    }

    @Override
    protected void doExecute(UpdateCalendarJobAction.Request request, ActionListener<PutCalendarAction.Response> listener) {
        ClusterState clusterState = clusterService.state();
        MlMetadata mlMetadata = clusterState.getMetaData().custom(MLMetadataField.TYPE);
        if (mlMetadata == null) {
            mlMetadata = MlMetadata.EMPTY_METADATA;
        }

        Set<String> jobIdsToAdd = Strings.tokenizeByCommaToSet(request.getJobIdsToAddExpression());
        Set<String> jobIdsToRemove = Strings.tokenizeByCommaToSet(request.getJobIdsToRemoveExpression());

        jobProvider.updateCalendar(request.getCalendarId(), jobIdsToAdd, jobIdsToRemove, mlMetadata,
                c -> {
                    jobManager.updateProcessOnCalendarChanged(c.getJobIds());
                    listener.onResponse(new PutCalendarAction.Response(c));
                }, listener::onFailure);
    }
}
