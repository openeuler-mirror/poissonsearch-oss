/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.action;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkAction;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.core.ml.action.UpdateModelSnapshotAction;
import org.elasticsearch.xpack.core.ml.job.messages.Messages;
import org.elasticsearch.xpack.core.ml.job.persistence.ElasticsearchMappings;
import org.elasticsearch.xpack.core.ml.job.persistence.JobProvider;
import org.elasticsearch.xpack.core.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.core.ml.job.results.Result;

import java.io.IOException;
import java.util.function.Consumer;

import static org.elasticsearch.xpack.core.ClientHelper.ML_ORIGIN;
import static org.elasticsearch.xpack.core.ClientHelper.executeAsyncWithOrigin;

public class TransportUpdateModelSnapshotAction extends HandledTransportAction<UpdateModelSnapshotAction.Request,
        UpdateModelSnapshotAction.Response> {

    private final JobProvider jobProvider;
    private final Client client;

    @Inject
    public TransportUpdateModelSnapshotAction(Settings settings, TransportService transportService, ThreadPool threadPool,
                                              ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                              JobProvider jobProvider, Client client) {
        super(settings, UpdateModelSnapshotAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
                UpdateModelSnapshotAction.Request::new);
        this.jobProvider = jobProvider;
        this.client = client;
    }

    @Override
    protected void doExecute(UpdateModelSnapshotAction.Request request, ActionListener<UpdateModelSnapshotAction.Response> listener) {
        logger.debug("Received request to update model snapshot [{}] for job [{}]", request.getSnapshotId(), request.getJobId());
        jobProvider.getModelSnapshot(request.getJobId(), request.getSnapshotId(), modelSnapshot -> {
            if (modelSnapshot == null) {
                listener.onFailure(new ResourceNotFoundException(Messages.getMessage(
                        Messages.REST_NO_SUCH_MODEL_SNAPSHOT, request.getSnapshotId(), request.getJobId())));
            } else {
                Result<ModelSnapshot> updatedSnapshot = applyUpdate(request, modelSnapshot);
                indexModelSnapshot(updatedSnapshot, b -> {
                    // The quantiles can be large, and totally dominate the output -
                    // it's clearer to remove them
                    listener.onResponse(new UpdateModelSnapshotAction.Response(
                            new ModelSnapshot.Builder(updatedSnapshot.result).setQuantiles(null).build()));
                }, listener::onFailure);
            }
        }, listener::onFailure);
    }

    private static Result<ModelSnapshot> applyUpdate(UpdateModelSnapshotAction.Request request, Result<ModelSnapshot> target) {
        ModelSnapshot.Builder updatedSnapshotBuilder = new ModelSnapshot.Builder(target.result);
        if (request.getDescription() != null) {
            updatedSnapshotBuilder.setDescription(request.getDescription());
        }
        if (request.getRetain() != null) {
            updatedSnapshotBuilder.setRetain(request.getRetain());
        }
        return new Result(target.index, updatedSnapshotBuilder.build());
    }

    private void indexModelSnapshot(Result<ModelSnapshot> modelSnapshot, Consumer<Boolean> handler, Consumer<Exception> errorHandler) {
        IndexRequest indexRequest = new IndexRequest(modelSnapshot.index, ElasticsearchMappings.DOC_TYPE,
                ModelSnapshot.documentId(modelSnapshot.result));
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            modelSnapshot.result.toXContent(builder, ToXContent.EMPTY_PARAMS);
            indexRequest.source(builder);
        } catch (IOException e) {
            errorHandler.accept(e);
            return;
        }
        BulkRequestBuilder bulkRequestBuilder = client.prepareBulk();
        bulkRequestBuilder.add(indexRequest);
        bulkRequestBuilder.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);
        executeAsyncWithOrigin(client, ML_ORIGIN, BulkAction.INSTANCE, bulkRequestBuilder.request(),
                new ActionListener<BulkResponse>() {
                    @Override
                    public void onResponse(BulkResponse indexResponse) {
                        handler.accept(true);
                    }

                    @Override
                    public void onFailure(Exception e) {
                        errorHandler.accept(e);
                    }
                });
    }
}
