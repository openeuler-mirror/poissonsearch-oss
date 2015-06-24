/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.watch;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.engine.DocumentMissingException;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.watcher.WatcherException;
import org.elasticsearch.watcher.support.TemplateUtils;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 */
public class WatchStore extends AbstractComponent {

    public static final String INDEX = ".watches";
    public static final String INDEX_TEMPLATE = "watches";
    public static final String DOC_TYPE = "watch";

    private final ClientProxy client;
    private final TemplateUtils templateUtils;
    private final Watch.Parser watchParser;

    private final ConcurrentMap<String, Watch> watches;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private final int scrollSize;
    private final TimeValue scrollTimeout;

    @Inject
    public WatchStore(Settings settings, ClientProxy client, TemplateUtils templateUtils, Watch.Parser watchParser) {
        super(settings);
        this.client = client;
        this.templateUtils = templateUtils;
        this.watchParser = watchParser;
        this.watches = ConcurrentCollections.newConcurrentMap();

        this.scrollTimeout = settings.getAsTime("watcher.watch.scroll.timeout", TimeValue.timeValueSeconds(30));
        this.scrollSize = settings.getAsInt("watcher.watch.scroll.size", 100);
    }

    public void start(ClusterState state) {
        if (started.get()) {
            logger.debug("watch store already started");
            return;
        }

        IndexMetaData watchesIndexMetaData = state.getMetaData().index(INDEX);
        if (watchesIndexMetaData != null) {
            try {
                int count = loadWatches(watchesIndexMetaData.numberOfShards());
                logger.debug("loaded [{}] watches from the watches index [{}]", count, INDEX);
                templateUtils.putTemplate(INDEX_TEMPLATE, null);
                started.set(true);
            } catch (Exception e) {
                logger.debug("failed to load watches for watch index [{}]", e, INDEX);
                watches.clear();
                throw e;
            }
        } else {
            templateUtils.putTemplate(INDEX_TEMPLATE, null);
            started.set(true);
        }
    }

    public boolean validate(ClusterState state) {
        IndexMetaData watchesIndexMetaData = state.getMetaData().index(INDEX);
        if (watchesIndexMetaData == null) {
            logger.debug("watches index [{}] doesn't exist, so we can start", INDEX);
            return true;
        }
        if (state.routingTable().index(INDEX).allPrimaryShardsActive()) {
            logger.debug("watches index [{}] exists and all primary shards are started, so we can start", INDEX);
            return true;
        }
        return false;
    }

    public boolean started() {
        return started.get();
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            watches.clear();
            logger.info("stopped watch store");
        }
    }

    /**
     * Returns the watch with the specified id otherwise <code>null</code> is returned.
     */
    public Watch get(String id) {
        ensureStarted();
        return watches.get(id);
    }

    /**
     * Creates an watch if this watch already exists it will be overwritten
     */
    public WatchPut put(Watch watch) {
        ensureStarted();
        IndexRequest indexRequest = createIndexRequest(watch.id(), watch.getAsBytes(), Versions.MATCH_ANY);
        IndexResponse response = client.index(indexRequest, (TimeValue) null);
        watch.status().version(response.getVersion());
        watch.version(response.getVersion());
        Watch previous = watches.put(watch.id(), watch);
        return new WatchPut(previous, watch, response);
    }

    /**
     * Updates and persists the status of the given watch
     */
    public void updateStatus(Watch watch) throws IOException {
        ensureStarted();
        if (!watch.status().dirty()) {
            return;
        }

        // at the moment we store the status together with the watch,
        // so we just need to update the watch itself
        // TODO: consider storing the status in a different documment (watch_status doc) (must smaller docs... faster for frequent updates)
        XContentBuilder source = JsonXContent.contentBuilder().
                startObject()
                    .field(Watch.Field.STATUS.getPreferredName(), watch.status(), ToXContent.EMPTY_PARAMS)
                .endObject();
        UpdateRequest updateRequest = new UpdateRequest(INDEX, DOC_TYPE, watch.id());
        updateRequest.doc(source);
        updateRequest.version(watch.version());
        try {
            UpdateResponse response = client.update(updateRequest);
            watch.status().version(response.getVersion());
            watch.version(response.getVersion());
            watch.status().resetDirty();
            // Don't need to update the watches, since we are working on an instance from it.
        } catch (DocumentMissingException dme) {
            throw new WatchMissingException("could not update watch [{}] as it could not be found", watch.id(), dme);
        }
    }

    /**
     * Deletes the watch with the specified id if exists
     */
    public WatchDelete delete(String id, boolean force) {
        ensureStarted();
        Watch watch = watches.remove(id);
        // even if the watch was not found in the watch map, we should still try to delete it
        // from the index, just to make sure we don't leave traces of it
        DeleteRequest request = new DeleteRequest(INDEX, DOC_TYPE, id);
        if (watch != null && !force) {
            request.version(watch.version());
        }
        DeleteResponse response = client.delete(request);
        // Another operation may hold the Watch instance, so lets set the version for consistency:
        if (watch != null) {
            watch.version(response.getVersion());
        }
        return new WatchDelete(response);
    }

    public ConcurrentMap<String, Watch> watches() {
        return watches;
    }

    IndexRequest createIndexRequest(String id, BytesReference source, long version) {
        IndexRequest indexRequest = new IndexRequest(INDEX, DOC_TYPE, id);
        // TODO (2.0 upgrade): move back to BytesReference instead of dealing with the array directly
        if (source.hasArray()) {
            indexRequest.source(source.array(), source.arrayOffset(), source.length());
        } else {
            indexRequest.source(source.toBytes());
        }
        indexRequest.version(version);
        return indexRequest;
    }

    /**
     * scrolls all the watch documents in the watches index, parses them, and loads them into
     * the given map.
     */
    int loadWatches(int numPrimaryShards) {
        assert watches.isEmpty() : "no watches should reside, but there are [" + watches.size() + "] watches.";
        RefreshResponse refreshResponse = client.refresh(new RefreshRequest(INDEX));
        if (refreshResponse.getSuccessfulShards() < numPrimaryShards) {
            throw new WatcherException("not all required shards have been refreshed");
        }

        int count = 0;
        SearchRequest searchRequest = new SearchRequest(INDEX)
                .types(DOC_TYPE)
                .preference("_primary")
                .searchType(SearchType.SCAN)
                .scroll(scrollTimeout)
                .source(new SearchSourceBuilder()
                        .size(scrollSize)
                        .version(true));
        SearchResponse response = client.search(searchRequest, null);
        try {
            if (response.getTotalShards() != response.getSuccessfulShards()) {
                throw new ElasticsearchException("Partial response while loading watches");
            }

            if (response.getHits().getTotalHits() > 0) {
                response = client.searchScroll(response.getScrollId(), scrollTimeout);
                while (response.getHits().hits().length != 0) {
                    for (SearchHit hit : response.getHits()) {
                        String id = hit.getId();
                        try {
                            Watch watch = watchParser.parse(id, true, hit.getSourceRef());
                            watch.status().version(hit.version());
                            watch.version(hit.version());
                            watches.put(id, watch);
                            count++;
                        } catch (Exception e) {
                            logger.error("couldn't load watch [{}], ignoring it...", e, id);
                        }
                    }
                    response = client.searchScroll(response.getScrollId(), scrollTimeout);
                }
            }
        } finally {
            client.clearScroll(response.getScrollId());
        }
        return count;
    }

    private void ensureStarted() {
        if (!started.get()) {
            throw new IllegalStateException("watch store not started");
        }
    }

    public class WatchPut {

        private final Watch previous;
        private final Watch current;
        private final IndexResponse response;

        public WatchPut(Watch previous, Watch current, IndexResponse response) {
            this.current = current;
            this.previous = previous;
            this.response = response;
        }

        public Watch current() {
            return current;
        }

        public Watch previous() {
            return previous;
        }

        public IndexResponse indexResponse() {
            return response;
        }
    }

    public class WatchDelete {

        private final DeleteResponse response;

        public WatchDelete(DeleteResponse response) {
            this.response = response;
        }

        public DeleteResponse deleteResponse() {
            return response;
        }
    }

}
