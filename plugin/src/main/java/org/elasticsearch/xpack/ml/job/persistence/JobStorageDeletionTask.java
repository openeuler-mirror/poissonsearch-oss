/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.persistence;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.index.query.IdsQueryBuilder;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.DeleteByQueryAction;
import org.elasticsearch.index.reindex.DeleteByQueryRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.query.ConstantScoreQueryBuilder;
import org.elasticsearch.index.query.TermQueryBuilder;
import org.elasticsearch.rest.action.admin.indices.AliasesNotFoundException;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.xpack.ml.job.config.Job;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.CategorizerState;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.ModelSnapshot;
import org.elasticsearch.xpack.ml.job.process.autodetect.state.Quantiles;

import java.util.List;
import java.util.function.Consumer;

public class JobStorageDeletionTask extends Task {
    private final Logger logger;

    public JobStorageDeletionTask(long id, String type, String action, String description, TaskId parentTask) {
        super(id, type, action, description, parentTask);
        this.logger = Loggers.getLogger(getClass());
    }

    public void delete(String jobId, Client client, ClusterState state,
                       CheckedConsumer<Boolean, Exception> finishedHandler,
                       Consumer<Exception> failureHandler) {

        final String indexName = AnomalyDetectorsIndex.getPhysicalIndexFromState(state, jobId);
        final String indexPattern = indexName + "-*";

        ActionListener<Boolean> deleteAliasHandler = ActionListener.wrap(finishedHandler, failureHandler);

        // Step 5. DBQ state done, delete the aliases
        ActionListener<BulkByScrollResponse> dbqHandler = ActionListener.wrap(
                bulkByScrollResponse -> {
                    if (bulkByScrollResponse.isTimedOut()) {
                        logger.warn("[{}] DeleteByQuery for indices [{}, {}] timed out.", jobId, indexName, indexPattern);
                    }
                    if (!bulkByScrollResponse.getBulkFailures().isEmpty()) {
                        logger.warn("[{}] {} failures and {} conflicts encountered while running DeleteByQuery on indices [{}, {}].",
                                jobId, bulkByScrollResponse.getBulkFailures().size(), bulkByScrollResponse.getVersionConflicts(),
                                indexName, indexPattern);
                        for (BulkItemResponse.Failure failure : bulkByScrollResponse.getBulkFailures()) {
                            logger.warn("DBQ failure: " + failure);
                        }
                    }
                    deleteAliases(jobId, client, deleteAliasHandler);
                },
                failureHandler);

        // Step 4. Delete categorizer state done, DeleteByQuery on the index, matching all docs with the right job_id
        ActionListener<Boolean> deleteCategorizerStateHandler = ActionListener.wrap(
                response -> {
                    logger.info("Running DBQ on [" + indexName + "," + indexPattern + "] for job [" + jobId + "]");
                    SearchRequest searchRequest = new SearchRequest(indexName, indexPattern);
                    DeleteByQueryRequest request = new DeleteByQueryRequest(searchRequest);
                    ConstantScoreQueryBuilder query =
                            new ConstantScoreQueryBuilder(new TermQueryBuilder(Job.ID.getPreferredName(), jobId));
                    searchRequest.source(new SearchSourceBuilder().query(query));
                    searchRequest.indicesOptions(JobProvider.addIgnoreUnavailable(IndicesOptions.lenientExpandOpen()));
                    request.setSlices(5);
                    request.setAbortOnVersionConflict(false);
                    request.setRefresh(true);

                    client.execute(DeleteByQueryAction.INSTANCE, request, dbqHandler);
                },
                failureHandler);

        // Step 3. Delete quantiles done, delete the categorizer state
        ActionListener<Boolean> deleteQuantilesHandler = ActionListener.wrap(
                response -> deleteCategorizerState(jobId, client, 1, deleteCategorizerStateHandler),
                failureHandler);

        // Step 2. Delete state done, delete the quantiles
        ActionListener<BulkResponse> deleteStateHandler = ActionListener.wrap(
                bulkResponse -> deleteQuantiles(jobId, client, deleteQuantilesHandler),
                failureHandler);

        // Step 1. Delete the model state
        deleteModelState(jobId, client, deleteStateHandler);
    }

    private void deleteQuantiles(String jobId, Client client, ActionListener<Boolean> finishedHandler) {
        // The quantiles type and doc ID changed in v5.5 so delete both the old and new format
        SearchRequest searchRequest = new SearchRequest(AnomalyDetectorsIndex.jobStateIndexName());
        DeleteByQueryRequest request = new DeleteByQueryRequest(searchRequest);
        // Just use ID here, not type, as trying to delete different types spams the logs with an exception stack trace
        IdsQueryBuilder query = new IdsQueryBuilder().addIds(Quantiles.documentId(jobId),
                // TODO: remove in 7.0
                Quantiles.v54DocumentId(jobId));
        searchRequest.source(new SearchSourceBuilder().query(query));
        searchRequest.indicesOptions(JobProvider.addIgnoreUnavailable(IndicesOptions.lenientExpandOpen()));
        request.setAbortOnVersionConflict(false);

        client.execute(DeleteByQueryAction.INSTANCE, request, ActionListener.wrap(
                response -> finishedHandler.onResponse(true),
                e -> {
                    // It's not a problem for us if the index wasn't found - it's equivalent to document not found
                    if (e instanceof IndexNotFoundException) {
                        finishedHandler.onResponse(true);
                    } else {
                        finishedHandler.onFailure(e);
                    }
                }));
    }

    private void deleteModelState(String jobId, Client client, ActionListener<BulkResponse> listener) {
        JobProvider jobProvider = new JobProvider(client, Settings.EMPTY);
        jobProvider.modelSnapshots(jobId, 0, 10000,
                page -> {
                    List<ModelSnapshot> deleteCandidates = page.results();
                    JobDataDeleter deleter = new JobDataDeleter(client, jobId);
                    deleter.deleteModelSnapshots(deleteCandidates, listener);
                },
                listener::onFailure);
    }

    private void deleteCategorizerState(String jobId, Client client, int docNum, ActionListener<Boolean> finishedHandler) {
        // The categorizer state type and doc ID changed in v5.5 so delete both the old and new format
        SearchRequest searchRequest = new SearchRequest(AnomalyDetectorsIndex.jobStateIndexName());
        DeleteByQueryRequest request = new DeleteByQueryRequest(searchRequest);
        // Just use ID here, not type, as trying to delete different types spams the logs with an exception stack trace
        IdsQueryBuilder query = new IdsQueryBuilder().addIds(CategorizerState.documentId(jobId, docNum),
                // TODO: remove in 7.0
                CategorizerState.v54DocumentId(jobId, docNum));
        searchRequest.source(new SearchSourceBuilder().query(query));
        searchRequest.indicesOptions(JobProvider.addIgnoreUnavailable(IndicesOptions.lenientExpandOpen()));
        request.setAbortOnVersionConflict(false);

        client.execute(DeleteByQueryAction.INSTANCE, request, ActionListener.wrap(
                response -> {
                    // If we successfully deleted a document try the next one; if not we're done
                    if (response.getDeleted() > 0) {
                        // There's an assumption here that there won't be very many categorizer
                        // state documents, so the recursion won't go more than, say, 5 levels deep
                        deleteCategorizerState(jobId, client, docNum + 1, finishedHandler);
                        return;
                    }
                    finishedHandler.onResponse(true);
                },
                e -> {
                    // It's not a problem for us if the index wasn't found - it's equivalent to document not found
                    if (e instanceof IndexNotFoundException) {
                        finishedHandler.onResponse(true);
                    } else {
                        finishedHandler.onFailure(e);
                    }
                }));
    }

    private void deleteAliases(String jobId, Client client, ActionListener<Boolean> finishedHandler) {
        final String readAliasName = AnomalyDetectorsIndex.jobResultsAliasedName(jobId);
        final String writeAliasName = AnomalyDetectorsIndex.resultsWriteAlias(jobId);
        final String indexPattern = AnomalyDetectorsIndex.jobResultsIndexPrefix() + "*";

        IndicesAliasesRequest request = new IndicesAliasesRequest().addAliasAction(
                IndicesAliasesRequest.AliasActions.remove().aliases(readAliasName, writeAliasName).indices(indexPattern));
        client.admin().indices().aliases(request, ActionListener.wrap(
                response -> finishedHandler.onResponse(true),
                e -> {
                    if (e instanceof AliasesNotFoundException) {
                        logger.warn("[{}] Aliases {} not found. Continuing to delete job.", jobId,
                                ((AliasesNotFoundException) e).getResourceId());
                        finishedHandler.onResponse(true);
                    } else if (e instanceof IndexNotFoundException) {
                        logger.warn("[{}] Index [{}] referenced by alias not found. Continuing to delete job.", jobId,
                                ((IndexNotFoundException) e).getIndex().getName());
                        finishedHandler.onResponse(true);
                    } else {
                        // all other exceptions should die
                        logger.error("[" + jobId + "] Failed to delete aliases [" + readAliasName + ", " + writeAliasName + "].", e);
                        finishedHandler.onFailure(e);
                    }
                }));
    }
}
