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

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.TemplateScript;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.elasticsearch.common.xcontent.XContentHelper.createParser;

/**
 * Instances of this class execute a collection of search intents (read: user
 * supplied query parameters) against a set of possible search requests (read:
 * search specifications, expressed as query/search request templates) and
 * compares the result against a set of annotated documents per search intent.
 *
 * If any documents are returned that haven't been annotated the document id of
 * those is returned per search intent.
 *
 * The resulting search quality is computed in terms of precision at n and
 * returned for each search specification for the full set of search intents as
 * averaged precision at n.
 */
public class TransportRankEvalAction extends HandledTransportAction<RankEvalRequest, RankEvalResponse> {
    private Client client;
    private ScriptService scriptService;
    Queue<RequestTask> taskQueue = new ConcurrentLinkedQueue<>();
    private NamedXContentRegistry namedXContentRegistry;

    @Inject
    public TransportRankEvalAction(Settings settings, ThreadPool threadPool, ActionFilters actionFilters,
            IndexNameExpressionResolver indexNameExpressionResolver, Client client, TransportService transportService,
            ScriptService scriptService, NamedXContentRegistry namedXContentRegistry) {
        super(settings, RankEvalAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
                RankEvalRequest::new);
        this.scriptService = scriptService;
        this.namedXContentRegistry = namedXContentRegistry;
        this.client = client;
    }

    @Override
    protected void doExecute(RankEvalRequest request, ActionListener<RankEvalResponse> listener) {
        RankEvalSpec evaluationSpecification = request.getRankEvalSpec();
        List<String> indices = evaluationSpecification.getIndices();

        Collection<RatedRequest> ratedRequests = evaluationSpecification.getRatedRequests();
        AtomicInteger responseCounter = new AtomicInteger(ratedRequests.size());
        Map<String, EvalQueryQuality> partialResults = new ConcurrentHashMap<>(
                ratedRequests.size());
        Map<String, Exception> errors = new ConcurrentHashMap<>(ratedRequests.size());

        Map<String, TemplateScript.Factory> scriptsWithoutParams = new HashMap<>();
        for (Entry<String, Script> entry : evaluationSpecification.getTemplates().entrySet()) {
            scriptsWithoutParams.put(entry.getKey(), scriptService.compile(entry.getValue(), TemplateScript.CONTEXT));
        }

        for (RatedRequest ratedRequest : ratedRequests) {
            SearchSourceBuilder ratedSearchSource = ratedRequest.getTestRequest();
            if (ratedSearchSource == null) {
                Map<String, Object> params = ratedRequest.getParams();
                String templateId = ratedRequest.getTemplateId();
                TemplateScript.Factory templateScript = scriptsWithoutParams.get(templateId);
                String resolvedRequest = templateScript.newInstance(params).execute();
                try (XContentParser subParser = createParser(namedXContentRegistry, new BytesArray(resolvedRequest), XContentType.JSON)) {
                    ratedSearchSource = SearchSourceBuilder.fromXContent(subParser);
                } catch (IOException e) {
                    listener.onFailure(e);
                }
            }

            List<String> summaryFields = ratedRequest.getSummaryFields();
            if (summaryFields.isEmpty()) {
                ratedSearchSource.fetchSource(false);
            } else {
                ratedSearchSource.fetchSource(summaryFields.toArray(new String[summaryFields.size()]), new String[0]);
            }

            SearchRequest templatedRequest = new SearchRequest(indices.toArray(new String[indices.size()]), ratedSearchSource);
            final RankEvalActionListener searchListener = new RankEvalActionListener(listener,
                    evaluationSpecification.getMetric(), ratedRequest, partialResults, errors, responseCounter);
            RequestTask task = new RequestTask(templatedRequest, searchListener);
            taskQueue.add(task);
        }

        // Execute top n tasks, further execution is triggered in RankEvalActionListener
        for (int i = 0; (i < Math.min(ratedRequests.size(),
                evaluationSpecification.getMaxConcurrentSearches())); i++) {
            RequestTask task = taskQueue.poll();
            client.search(task.request, task.searchListener);
        }
    }

    private class RequestTask {
        private SearchRequest request;
        private RankEvalActionListener searchListener;

        RequestTask(SearchRequest request, RankEvalActionListener listener) {
            this.request = request;
            this.searchListener = listener;
        }
    }

    class RankEvalActionListener implements ActionListener<SearchResponse> {

        private ActionListener<RankEvalResponse> listener;
        private RatedRequest specification;
        private Map<String, EvalQueryQuality> requestDetails;
        private Map<String, Exception> errors;
        private EvaluationMetric metric;
        private AtomicInteger responseCounter;

        RankEvalActionListener(ActionListener<RankEvalResponse> listener,
                EvaluationMetric metric, RatedRequest specification,
                Map<String, EvalQueryQuality> details, Map<String, Exception> errors,
                AtomicInteger responseCounter) {
            this.listener = listener;
            this.metric = metric;
            this.errors = errors;
            this.specification = specification;
            this.requestDetails = details;
            this.responseCounter = responseCounter;
        }

        @Override
        public void onResponse(SearchResponse searchResponse) {
            SearchHit[] hits = searchResponse.getHits().getHits();
            EvalQueryQuality queryQuality = metric.evaluate(specification.getId(), hits,
                    specification.getRatedDocs());
            requestDetails.put(specification.getId(), queryQuality);
            handleResponse();
        }

        @Override
        public void onFailure(Exception exception) {
            errors.put(specification.getId(), exception);
            handleResponse();
        }

        private void handleResponse() {
            if (responseCounter.decrementAndGet() == 0) {
                listener.onResponse(new RankEvalResponse(metric.combine(requestDetails.values()), requestDetails, errors));
            } else {
                if (!taskQueue.isEmpty()) {
                    RequestTask task = taskQueue.poll();
                    client.search(task.request, task.searchListener);
                }
            }
        }
    }
}
