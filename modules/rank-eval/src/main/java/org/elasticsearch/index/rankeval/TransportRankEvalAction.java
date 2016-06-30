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

package org.elasticsearch.index.rankeval;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.TransportSearchAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.AutoCreateIndex;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.action.SearchTransportService;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.controller.SearchPhaseController;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Instances of this class execute a collection of search intents (read: user supplied query parameters) against a set of
 * possible search requests (read: search specifications, expressed as query/search request templates) and compares the result
 * against a set of annotated documents per search intent.
 * 
 * If any documents are returned that haven't been annotated the document id of those is returned per search intent.
 * 
 * The resulting search quality is computed in terms of precision at n and returned for each search specification for the full
 * set of search intents as averaged precision at n.
 * */
public class TransportRankEvalAction extends HandledTransportAction<RankEvalRequest, RankEvalResponse> {
    private SearchPhaseController searchPhaseController; 
    private TransportService transportService; 
    private SearchTransportService searchTransportService; 
    private ClusterService clusterService; 
    private ActionFilters actionFilters; 

    @Inject
    public TransportRankEvalAction(Settings settings, ThreadPool threadPool, ActionFilters actionFilters,
            IndexNameExpressionResolver indexNameExpressionResolver, ClusterService clusterService, ScriptService scriptService,
            AutoCreateIndex autoCreateIndex, Client client, TransportService transportService, SearchPhaseController searchPhaseController,
            SearchTransportService searchTransportService, NamedWriteableRegistry namedWriteableRegistry) {
        super(settings, RankEvalAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
                RankEvalRequest::new);
        this.searchPhaseController = searchPhaseController;
        this.transportService = transportService;
        this.searchTransportService = searchTransportService;
        this.clusterService = clusterService;
        this.actionFilters = actionFilters;

        namedWriteableRegistry.register(RankedListQualityMetric.class, PrecisionAtN.NAME, PrecisionAtN::new);
    }

    @Override
    protected void doExecute(RankEvalRequest request, ActionListener<RankEvalResponse> listener) {
        RankEvalResponse response = new RankEvalResponse();
        RankEvalSpec qualityTask = request.getRankEvalSpec();
        RankedListQualityMetric metric = qualityTask.getEvaluator();

        for (QuerySpec spec : qualityTask.getSpecifications()) {
            double qualitySum = 0;

            SearchSourceBuilder specRequest = spec.getTestRequest();
            String[] indices = new String[spec.getIndices().size()]; 
            spec.getIndices().toArray(indices);
            SearchRequest templatedRequest = new SearchRequest(indices, specRequest);


            Map<Integer, Collection<String>> unknownDocs = new HashMap<Integer, Collection<String>>();
            Collection<RatedQuery> intents = qualityTask.getIntents();
            for (RatedQuery intent : intents) {

                TransportSearchAction transportSearchAction = new TransportSearchAction(
                        settings, 
                        threadPool, 
                        searchPhaseController, 
                        transportService, 
                        searchTransportService, 
                        clusterService, 
                        actionFilters, 
                        indexNameExpressionResolver);
                ActionFuture<SearchResponse> searchResponse = transportSearchAction.execute(templatedRequest);
                SearchHits hits = searchResponse.actionGet().getHits();

                EvalQueryQuality intentQuality = metric.evaluate(hits.getHits(), intent);
                qualitySum += intentQuality.getQualityLevel();
                unknownDocs.put(intent.getIntentId(), intentQuality.getUnknownDocs());
            }
            response.addRankEvalResult(spec.getSpecId(), qualitySum / intents.size(), unknownDocs);
        }
        listener.onResponse(response);
    }
}
