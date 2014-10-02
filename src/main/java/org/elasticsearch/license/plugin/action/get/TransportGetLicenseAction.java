/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.action.get;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeReadOperationAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.RepositoriesMetaData;
import org.elasticsearch.cluster.metadata.RepositoryMetaData;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.repositories.RepositoryMissingException;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

public class TransportGetLicenseAction extends TransportMasterNodeReadOperationAction<GetLicenseRequest, GetLicenseResponse> {

    @Inject
    public TransportGetLicenseAction(Settings settings, TransportService transportService, ClusterService clusterService,
                                          ThreadPool threadPool, ActionFilters actionFilters) {
        super(settings, GetLicenseAction.NAME, transportService, clusterService, threadPool, actionFilters);
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.MANAGEMENT;
    }

    @Override
    protected GetLicenseRequest newRequest() {
        return new GetLicenseRequest();
    }

    @Override
    protected GetLicenseResponse newResponse() {
        return new GetLicenseResponse();
    }

    @Override
    protected ClusterBlockException checkBlock(GetLicenseRequest request, ClusterState state) {
        return state.blocks().indexBlockedException(ClusterBlockLevel.METADATA, "");
    }

    @Override
    protected void masterOperation(final GetLicenseRequest request, ClusterState state, final ActionListener<GetLicenseResponse> listener) throws ElasticsearchException {
        //TODO: impl after custom metadata impl
        /*
        MetaData metaData = state.metaData();
        RepositoriesMetaData repositories = metaData.custom(RepositoriesMetaData.TYPE);
        if (request.repositories().length == 0 || (request.repositories().length == 1 && "_all".equals(request.repositories()[0]))) {
            if (repositories != null) {
                listener.onResponse(new GetRepositoriesResponse(repositories.repositories()));
            } else {
                listener.onResponse(new GetRepositoriesResponse(ImmutableList.<RepositoryMetaData>of()));
            }
        } else {
            if (repositories != null) {
                ImmutableList.Builder<RepositoryMetaData> repositoryListBuilder = ImmutableList.builder();
                for (String repository : request.repositories()) {
                    RepositoryMetaData repositoryMetaData = repositories.repository(repository);
                    if (repositoryMetaData == null) {
                        listener.onFailure(new RepositoryMissingException(repository));
                        return;
                    }
                    repositoryListBuilder.add(repositoryMetaData);
                }
                listener.onResponse(new GetRepositoriesResponse(repositoryListBuilder.build()));
            } else {
                listener.onFailure(new RepositoryMissingException(request.repositories()[0]));
            }
        }*/
    }
}