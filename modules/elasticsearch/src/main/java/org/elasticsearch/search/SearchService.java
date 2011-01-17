/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.search;

import org.apache.lucene.search.TopDocs;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Unicode;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.util.concurrent.ConcurrentMapLong;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.shard.service.IndexShard;
import org.elasticsearch.indices.IndicesLifecycle;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.dfs.CachedDfSource;
import org.elasticsearch.search.dfs.DfsPhase;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.fetch.*;
import org.elasticsearch.search.internal.InternalScrollSearchRequest;
import org.elasticsearch.search.internal.InternalSearchRequest;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.query.*;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.timer.TimerService;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.elasticsearch.common.unit.TimeValue.*;

/**
 * @author kimchy (shay.banon)
 */
public class SearchService extends AbstractLifecycleComponent<SearchService> {

    private final ClusterService clusterService;

    private final IndicesService indicesService;

    private final TimerService timerService;

    private final ScriptService scriptService;

    private final DfsPhase dfsPhase;

    private final QueryPhase queryPhase;

    private final FetchPhase fetchPhase;


    private final long defaultKeepAlive;

    private final ScheduledFuture keepAliveReaper;


    private final AtomicLong idGenerator = new AtomicLong();

    private final CleanContextOnIndicesLifecycleListener indicesLifecycleListener = new CleanContextOnIndicesLifecycleListener();

    private final ConcurrentMapLong<SearchContext> activeContexts = ConcurrentCollections.newConcurrentMapLong();

    private final ImmutableMap<String, SearchParseElement> elementParsers;

    @Inject public SearchService(Settings settings, ClusterService clusterService, IndicesService indicesService, IndicesLifecycle indicesLifecycle, ThreadPool threadPool, TimerService timerService,
                                 ScriptService scriptService, DfsPhase dfsPhase, QueryPhase queryPhase, FetchPhase fetchPhase) {
        super(settings);
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.timerService = timerService;
        this.scriptService = scriptService;
        this.dfsPhase = dfsPhase;
        this.queryPhase = queryPhase;
        this.fetchPhase = fetchPhase;

        TimeValue keepAliveInterval = componentSettings.getAsTime("keep_alive_interval", timeValueMinutes(1));
        // we can have 5 minutes here, since we make sure to clean with search requests and when shard/index closes
        this.defaultKeepAlive = componentSettings.getAsTime("default_keep_alive", timeValueMinutes(5)).millis();

        Map<String, SearchParseElement> elementParsers = new HashMap<String, SearchParseElement>();
        elementParsers.putAll(dfsPhase.parseElements());
        elementParsers.putAll(queryPhase.parseElements());
        elementParsers.putAll(fetchPhase.parseElements());
        this.elementParsers = ImmutableMap.copyOf(elementParsers);
        indicesLifecycle.addListener(indicesLifecycleListener);

        this.keepAliveReaper = threadPool.scheduleWithFixedDelay(new Reaper(), keepAliveInterval);
    }

    @Override protected void doStart() throws ElasticSearchException {
    }

    @Override protected void doStop() throws ElasticSearchException {
        for (SearchContext context : activeContexts.values()) {
            freeContext(context);
        }
        activeContexts.clear();
    }

    @Override protected void doClose() throws ElasticSearchException {
        keepAliveReaper.cancel(false);
        indicesService.indicesLifecycle().removeListener(indicesLifecycleListener);
    }

    public void releaseContextsForIndex(Index index) {
        for (SearchContext context : activeContexts.values()) {
            if (context.shardTarget().index().equals(index.name())) {
                freeContext(context);
            }
        }
    }

    public void releaseContextsForShard(ShardId shardId) {
        for (SearchContext context : activeContexts.values()) {
            if (context.shardTarget().index().equals(shardId.index().name()) && context.shardTarget().shardId() == shardId.id()) {
                freeContext(context);
            }
        }
    }

    public DfsSearchResult executeDfsPhase(InternalSearchRequest request) throws ElasticSearchException {
        SearchContext context = createContext(request);
        activeContexts.put(context.id(), context);
        try {
            contextProcessing(context);
            dfsPhase.execute(context);
            contextProcessedSuccessfully(context);
            return context.dfsResult();
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    public QuerySearchResult executeQueryPhase(InternalSearchRequest request) throws ElasticSearchException {
        SearchContext context = createContext(request);
        activeContexts.put(context.id(), context);
        try {
            contextProcessing(context);
            queryPhase.execute(context);
            contextProcessedSuccessfully(context);
            return context.queryResult();
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    public ScrollQuerySearchResult executeQueryPhase(InternalScrollSearchRequest request) throws ElasticSearchException {
        SearchContext context = findContext(request.id());
        try {
            contextProcessing(context);
            processScroll(request, context);
            contextProcessedSuccessfully(context);
            queryPhase.execute(context);
            return new ScrollQuerySearchResult(context.queryResult(), context.shardTarget());
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    public QuerySearchResult executeQueryPhase(QuerySearchRequest request) throws ElasticSearchException {
        SearchContext context = findContext(request.id());
        contextProcessing(context);
        try {
            context.searcher().dfSource(new CachedDfSource(request.dfs(), context.similarityService().defaultSearchSimilarity()));
        } catch (IOException e) {
            freeContext(context);
            cleanContext(context);
            throw new QueryPhaseExecutionException(context, "Failed to set aggregated df", e);
        }
        try {
            queryPhase.execute(context);
            contextProcessedSuccessfully(context);
            return context.queryResult();
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    public QueryFetchSearchResult executeFetchPhase(InternalSearchRequest request) throws ElasticSearchException {
        SearchContext context = createContext(request);
        activeContexts.put(context.id(), context);
        contextProcessing(context);
        try {
            queryPhase.execute(context);
            shortcutDocIdsToLoad(context);
            fetchPhase.execute(context);
            if (context.scroll() == null) {
                freeContext(context.id());
            } else {
                contextProcessedSuccessfully(context);
            }
            return new QueryFetchSearchResult(context.queryResult(), context.fetchResult());
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    public QueryFetchSearchResult executeFetchPhase(QuerySearchRequest request) throws ElasticSearchException {
        SearchContext context = findContext(request.id());
        contextProcessing(context);
        try {
            context.searcher().dfSource(new CachedDfSource(request.dfs(), context.similarityService().defaultSearchSimilarity()));
        } catch (IOException e) {
            freeContext(context);
            cleanContext(context);
            throw new QueryPhaseExecutionException(context, "Failed to set aggregated df", e);
        }
        try {
            queryPhase.execute(context);
            shortcutDocIdsToLoad(context);
            fetchPhase.execute(context);
            if (context.scroll() == null) {
                freeContext(request.id());
            } else {
                contextProcessedSuccessfully(context);
            }
            return new QueryFetchSearchResult(context.queryResult(), context.fetchResult());
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    public ScrollQueryFetchSearchResult executeFetchPhase(InternalScrollSearchRequest request) throws ElasticSearchException {
        SearchContext context = findContext(request.id());
        contextProcessing(context);
        try {
            processScroll(request, context);
            queryPhase.execute(context);
            shortcutDocIdsToLoad(context);
            fetchPhase.execute(context);
            if (context.scroll() == null) {
                freeContext(request.id());
            } else {
                contextProcessedSuccessfully(context);
            }
            return new ScrollQueryFetchSearchResult(new QueryFetchSearchResult(context.queryResult(), context.fetchResult()), context.shardTarget());
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    public FetchSearchResult executeFetchPhase(FetchSearchRequest request) throws ElasticSearchException {
        SearchContext context = findContext(request.id());
        contextProcessing(context);
        try {
            context.docIdsToLoad(request.docIds(), 0, request.docIdsSize());
            fetchPhase.execute(context);
            if (context.scroll() == null) {
                freeContext(request.id());
            } else {
                contextProcessedSuccessfully(context);
            }
            return context.fetchResult();
        } catch (RuntimeException e) {
            freeContext(context);
            throw e;
        } finally {
            cleanContext(context);
        }
    }

    private SearchContext findContext(long id) throws SearchContextMissingException {
        SearchContext context = activeContexts.get(id);
        if (context == null) {
            throw new SearchContextMissingException(id);
        }
        SearchContext.setCurrent(context);
        return context;
    }

    private SearchContext createContext(InternalSearchRequest request) throws ElasticSearchException {
        IndexService indexService = indicesService.indexServiceSafe(request.index());
        IndexShard indexShard = indexService.shardSafe(request.shardId());

        SearchShardTarget shardTarget = new SearchShardTarget(clusterService.localNode().id(), request.index(), request.shardId());

        Engine.Searcher engineSearcher = indexShard.searcher();
        SearchContext context = new SearchContext(idGenerator.incrementAndGet(), shardTarget, request.numberOfShards(), request.timeout(), request.types(), engineSearcher, indexService, scriptService);
        SearchContext.setCurrent(context);
        try {
            context.scroll(request.scroll());

            parseSource(context, request.source(), request.sourceOffset(), request.sourceLength());
            parseSource(context, request.extraSource(), request.extraSourceOffset(), request.extraSourceLength());

            // if the from and size are still not set, default them
            if (context.from() == -1) {
                context.from(0);
            }
            if (context.size() == -1) {
                context.size(10);
            }

            // pre process
            dfsPhase.preProcess(context);
            queryPhase.preProcess(context);
            fetchPhase.preProcess(context);

            // compute the context keep alive
            long keepAlive = defaultKeepAlive;
            if (request.scroll() != null && request.scroll().keepAlive() != null) {
                keepAlive = request.scroll().keepAlive().millis();
            }
            context.keepAlive(keepAlive);
        } catch (RuntimeException e) {
            context.release();
            throw e;
        }

        return context;
    }

    public void freeContext(long id) {
        SearchContext context = activeContexts.remove(id);
        if (context == null) {
            return;
        }
        freeContext(context);
    }

    private void freeContext(SearchContext context) {
        activeContexts.remove(context.id());
        context.release();
    }

    private void contextProcessing(SearchContext context) {
        // disable timeout while executing a search
        context.accessed(-1);
    }

    private void contextProcessedSuccessfully(SearchContext context) {
        context.accessed(timerService.estimatedTimeInMillis());
    }

    private void cleanContext(SearchContext context) {
        SearchContext.removeCurrent();
    }

    private void parseSource(SearchContext context, byte[] source, int offset, int length) throws SearchParseException {
        // nothing to parse...
        if (source == null || length == 0) {
            return;
        }
        try {
            XContentParser parser = XContentFactory.xContent(source, offset, length).createParser(source, offset, length);
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    String fieldName = parser.currentName();
                    parser.nextToken();
                    SearchParseElement element = elementParsers.get(fieldName);
                    if (element == null) {
                        throw new SearchParseException(context, "No parser for element [" + fieldName + "]");
                    }
                    element.parse(parser, context);
                } else if (token == null) {
                    break;
                }
            }
            parser.close();
        } catch (Exception e) {
            String sSource = "_na_";
            try {
                sSource = Unicode.fromBytes(source, offset, length);
            } catch (Throwable e1) {
                // ignore
            }
            throw new SearchParseException(context, "Failed to parse source [" + sSource + "]", e);
        }
    }

    private static final int[] EMPTY_DOC_IDS = new int[0];

    private void shortcutDocIdsToLoad(SearchContext context) {
        TopDocs topDocs = context.queryResult().topDocs();
        if (topDocs.scoreDocs.length < context.from()) {
            // no more docs...
            context.docIdsToLoad(EMPTY_DOC_IDS, 0, 0);
            return;
        }
        int totalSize = context.from() + context.size();
        int[] docIdsToLoad = new int[context.size()];
        int counter = 0;
        for (int i = context.from(); i < totalSize; i++) {
            if (i < topDocs.scoreDocs.length) {
                docIdsToLoad[counter] = topDocs.scoreDocs[i].doc;
            } else {
                break;
            }
            counter++;
        }
        context.docIdsToLoad(docIdsToLoad, 0, counter);
    }

    private void processScroll(InternalScrollSearchRequest request, SearchContext context) {
        // process scroll
        context.from(context.from() + context.size());
        context.scroll(request.scroll());
        // update the context keep alive based on the new scroll value
        if (request.scroll() != null && request.scroll().keepAlive() != null) {
            context.keepAlive(request.scroll().keepAlive().millis());
        }
    }

    class CleanContextOnIndicesLifecycleListener extends IndicesLifecycle.Listener {

        @Override public void beforeIndexClosed(IndexService indexService, boolean delete) {
            releaseContextsForIndex(indexService.index());
        }

        @Override public void beforeIndexShardClosed(ShardId shardId, @Nullable IndexShard indexShard, boolean delete) {
            releaseContextsForShard(shardId);
        }
    }

    class Reaper implements Runnable {
        @Override public void run() {
            for (SearchContext context : activeContexts.values()) {
                if (context.lastAccessTime() == -1) { // its being processed or timeout is disabled
                    continue;
                }
                if ((timerService.estimatedTimeInMillis() - context.lastAccessTime() > context.keepAlive())) {
                    freeContext(context);
                }
            }
        }
    }
}
