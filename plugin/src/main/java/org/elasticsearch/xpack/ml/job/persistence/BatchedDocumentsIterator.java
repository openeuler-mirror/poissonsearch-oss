/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.persistence;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.xpack.ml.job.results.Result;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * An iterator useful to fetch a big number of documents of type T
 * and iterate through them in batches.
 */
public abstract class BatchedDocumentsIterator<T>  {
    private static final Logger LOGGER = Loggers.getLogger(BatchedDocumentsIterator.class);

    private static final String CONTEXT_ALIVE_DURATION = "5m";
    private static final int BATCH_SIZE = 10000;

    private final Client client;
    private final String index;
    private final ResultsFilterBuilder filterBuilder;
    private volatile long count;
    private volatile long totalHits;
    private volatile String scrollId;
    private volatile boolean isScrollInitialised;

    public BatchedDocumentsIterator(Client client, String index) {
        this(client, index, new ResultsFilterBuilder());
    }

    protected BatchedDocumentsIterator(Client client, String index, QueryBuilder queryBuilder) {
        this(client, index, new ResultsFilterBuilder(queryBuilder));
    }

    private BatchedDocumentsIterator(Client client, String index, ResultsFilterBuilder resultsFilterBuilder) {
        this.client = Objects.requireNonNull(client);
        this.index = Objects.requireNonNull(index);
        totalHits = 0;
        count = 0;
        filterBuilder = Objects.requireNonNull(resultsFilterBuilder);
        isScrollInitialised = false;
    }

    /**
     * Query documents whose timestamp is within the given time range
     *
     * @param startEpochMs the start time as epoch milliseconds (inclusive)
     * @param endEpochMs the end time as epoch milliseconds (exclusive)
     * @return the iterator itself
     */
    public BatchedDocumentsIterator<T> timeRange(long startEpochMs, long endEpochMs) {
        filterBuilder.timeRange(Result.TIMESTAMP.getPreferredName(), startEpochMs, endEpochMs);
        return this;
    }

    /**
     * Sets whether interim results should be included
     *
     * @param includeInterim Whether interim results should be included
     */
    public BatchedDocumentsIterator<T> includeInterim(boolean includeInterim) {
        filterBuilder.interim(includeInterim);
        return this;
    }

    /**
     * Returns {@code true} if the iteration has more elements.
     * (In other words, returns {@code true} if {@link #next} would
     * return an element rather than throwing an exception.)
     *
     * @return {@code true} if the iteration has more elements
     */
    public boolean hasNext() {
        return !isScrollInitialised || count != totalHits;
    }

    /**
     * The first time next() is called, the search will be performed and the first
     * batch will be returned. Any subsequent call will return the following batches.
     * <p>
     * Note that in some implementations it is possible that when there are no
     * results at all, the first time this method is called an empty {@code Deque} is returned.
     *
     * @return a {@code Deque} with the next batch of documents
     * @throws NoSuchElementException if the iteration has no more elements
     */
    public Deque<T> next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }

        SearchResponse searchResponse;
        if (scrollId == null) {
            searchResponse = initScroll();
        } else {
            SearchScrollRequest searchScrollRequest = new SearchScrollRequest(scrollId).scroll(CONTEXT_ALIVE_DURATION);
            searchResponse = client.searchScroll(searchScrollRequest).actionGet();
        }
        scrollId = searchResponse.getScrollId();
        return mapHits(searchResponse);
    }

    private SearchResponse initScroll() {
        LOGGER.trace("ES API CALL: search all of type {} from index {}", getType(), index);

        isScrollInitialised = true;

        SearchRequest searchRequest = new SearchRequest(index);
        searchRequest.indicesOptions(JobProvider.addIgnoreUnavailable(SearchRequest.DEFAULT_INDICES_OPTIONS));
        searchRequest.types(getType());
        searchRequest.scroll(CONTEXT_ALIVE_DURATION);
        searchRequest.source(new SearchSourceBuilder()
                .size(BATCH_SIZE)
                .query(filterBuilder.build())
                .sort(SortBuilders.fieldSort(ElasticsearchMappings.ES_DOC)));

        SearchResponse searchResponse = client.search(searchRequest).actionGet();
        totalHits = searchResponse.getHits().getTotalHits();
        scrollId = searchResponse.getScrollId();
        return searchResponse;
    }

    private Deque<T> mapHits(SearchResponse searchResponse) {
        Deque<T> results = new ArrayDeque<>();

        SearchHit[] hits = searchResponse.getHits().getHits();
        for (SearchHit hit : hits) {
            T mapped = map(hit);
            if (mapped != null) {
                results.add(mapped);
            }
        }
        count += hits.length;

        if (!hasNext() && scrollId != null) {
            client.prepareClearScroll().setScrollIds(Arrays.asList(scrollId)).get();
        }
        return results;
    }

    protected abstract String getType();

    /**
     * Maps the search hit to the document type
     * @param hit
     *            the search hit
     * @return The mapped document or {@code null} if the mapping failed
     */
    protected abstract T map(SearchHit hit);
}
