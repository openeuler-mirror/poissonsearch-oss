/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.transform.search;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.support.WatcherUtils;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.transform.ExecutableTransform;
import org.elasticsearch.watcher.watch.Payload;

import java.io.IOException;

/**
 *
 */
public class ExecutableSearchTransform extends ExecutableTransform<SearchTransform, SearchTransform.Result> {

    public static final SearchType DEFAULT_SEARCH_TYPE = SearchType.QUERY_THEN_FETCH;

    protected final ClientProxy client;

    public ExecutableSearchTransform(SearchTransform transform, ESLogger logger, ClientProxy client) {
        super(transform, logger);
        this.client = client;
    }

    @Override
    public SearchTransform.Result execute(WatchExecutionContext ctx, Payload payload) throws IOException {
        SearchRequest req = WatcherUtils.createSearchRequestFromPrototype(transform.request, ctx, payload);
        SearchResponse resp = client.search(req);
        return new SearchTransform.Result(req, new Payload.XContent(resp));
    }

}
