/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.input.search;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.input.ExecutableInput;
import org.elasticsearch.watcher.support.WatcherUtils;
import org.elasticsearch.watcher.support.XContentFilterKeysUtils;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.watch.Payload;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

/**
 * An input that executes search and returns the search response as the initial payload
 */
public class ExecutableSearchInput extends ExecutableInput<SearchInput, SearchInput.Result> {

    public static final SearchType DEFAULT_SEARCH_TYPE = SearchType.QUERY_THEN_FETCH;

    private final ScriptServiceProxy scriptService;
    private final ClientProxy client;

    public ExecutableSearchInput(SearchInput input, ESLogger logger, ScriptServiceProxy scriptService, ClientProxy client) {
        super(input, logger);
        this.scriptService = scriptService;
        this.client = client;
    }

    @Override
    public SearchInput.Result execute(WatchExecutionContext ctx) throws IOException {

        SearchRequest request = WatcherUtils.createSearchRequestFromPrototype(input.getSearchRequest(), ctx, scriptService, null);
        if (logger.isTraceEnabled()) {
            logger.trace("[{}] running query for [{}] [{}]", ctx.id(), ctx.watch().id(), XContentHelper.convertToJson(request.source(), false, true));
        }

        // actionGet deals properly with InterruptedException
        SearchResponse response = client.search(request);
        if (logger.isDebugEnabled()) {
            logger.debug("[{}] found [{}] hits", ctx.id(), ctx.watch().id(), response.getHits().getTotalHits());
            for (SearchHit hit : response.getHits()) {
                logger.debug("[{}] hit [{}]", ctx.id(), XContentHelper.toString(hit));
            }

        }

        final Payload payload;
        if (input.getExtractKeys() != null) {
            XContentBuilder builder = jsonBuilder().startObject().value(response).endObject();
            XContentParser parser = XContentHelper.createParser(builder.bytes());
            Map<String, Object> filteredKeys = XContentFilterKeysUtils.filterMapOrdered(input.getExtractKeys(), parser);
            payload = new Payload.Simple(filteredKeys);
        } else {
            payload = new Payload.XContent(response);
        }

        return new SearchInput.Result(request, payload);
    }
}
