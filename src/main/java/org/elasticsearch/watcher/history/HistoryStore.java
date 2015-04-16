/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.history;

import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.joda.time.format.DateTimeFormat;
import org.elasticsearch.common.joda.time.format.DateTimeFormatter;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.watcher.WatcherException;
import org.elasticsearch.watcher.support.TemplateUtils;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 */
public class HistoryStore extends AbstractComponent {

    public static final String INDEX_PREFIX = ".watch_history_";
    public static final String DOC_TYPE = "watch_record";
    public static final String INDEX_TEMPLATE_NAME = "watch_history";

    static final DateTimeFormatter indexTimeFormat = DateTimeFormat.forPattern("YYYY-MM-dd");

    private final ClientProxy client;
    private final TemplateUtils templateUtils;
    private final int scrollSize;
    private final TimeValue scrollTimeout;
    private final WatchRecord.Parser recordParser;
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock putUpdateLock = readWriteLock.readLock();
    private final Lock stopLock = readWriteLock.writeLock();
    private final AtomicBoolean started = new AtomicBoolean(false);

    @Inject
    public HistoryStore(Settings settings, ClientProxy client, TemplateUtils templateUtils, WatchRecord.Parser recordParser) {
        super(settings);
        this.client = client;
        this.templateUtils = templateUtils;
        this.recordParser = recordParser;
        this.scrollTimeout = componentSettings.getAsTime("scroll.timeout", TimeValue.timeValueSeconds(30));
        this.scrollSize = componentSettings.getAsInt("scroll.size", 100);
    }

    public void start() {
        started.set(true);
    }

    public void stop() {
        stopLock.lock(); //This will block while put or update actions are underway
        try {
            started.set(false);
        } finally {
            stopLock.unlock();
        }

    }

    public void put(WatchRecord watchRecord) throws HistoryException {
        if (!started.get()) {
            throw new HistoryException("unable to persist watch record history store is not ready");
        }
        String index = getHistoryIndexNameForTime(watchRecord.triggerEvent().triggeredTime());
        putUpdateLock.lock();
        try {
            IndexRequest request = new IndexRequest(index, DOC_TYPE, watchRecord.id().value())
                    .source(XContentFactory.jsonBuilder().value(watchRecord))
                    .opType(IndexRequest.OpType.CREATE);
            IndexResponse response = client.index(request);
            watchRecord.version(response.getVersion());
        } catch (IOException e) {
            throw new HistoryException("failed to persist watch record [" + watchRecord + "]", e);
        } finally {
            putUpdateLock.unlock();
        }
    }

    public void update(WatchRecord watchRecord) throws HistoryException {
        if (!started.get()) {
            throw new HistoryException("unable to persist watch record history store is not ready");
        }
        putUpdateLock.lock();
        try {
            BytesReference bytes = XContentFactory.jsonBuilder().value(watchRecord).bytes();
            IndexRequest request = new IndexRequest(getHistoryIndexNameForTime(watchRecord.triggerEvent().triggeredTime()), DOC_TYPE, watchRecord.id().value())
                    .source(bytes, true)
                    .version(watchRecord.version());
            IndexResponse response = client.index(request);
            watchRecord.version(response.getVersion());
            logger.debug("successfully updated watch record [{}]", watchRecord);
        } catch (IOException e) {
            throw new HistoryException("failed to update watch record [" + watchRecord + "]", e);
        } finally {
            putUpdateLock.unlock();
        }
    }

    /**
     * tries to load all watch records that await execution. If for some reason the records could not be
     * loaded (e.g. the not all primary shards of the history index are active), returns {@code null}.
     */
    public Collection<WatchRecord> loadRecords(ClusterState state, WatchRecord.State recordState) {
        String[] indices = state.metaData().concreteIndices(IndicesOptions.lenientExpandOpen(), INDEX_PREFIX + "*");
        if (indices.length == 0) {
            logger.debug("No .watch_history indices found. skipping loading awaiting watch records");
            templateUtils.ensureIndexTemplateIsLoaded(state, INDEX_TEMPLATE_NAME);
            return Collections.emptySet();
        }
        int numPrimaryShards = 0;
        for (String index : indices) {
            IndexMetaData indexMetaData = state.getMetaData().index(index);
            if (indexMetaData != null) {
                if (!state.routingTable().index(index).allPrimaryShardsActive()) {
                    logger.debug("Not all primary shards of the [{}] index are started. Schedule to retry loading awaiting watch records..", index);
                    return null;
                } else {
                    numPrimaryShards += indexMetaData.numberOfShards();
                }
            }
        }

        RefreshResponse refreshResponse = client.refresh(new RefreshRequest(INDEX_PREFIX + "*"));
        if (refreshResponse.getSuccessfulShards() < numPrimaryShards) {
            return null;
        }

        SearchRequest searchRequest = createScanSearchRequest(recordState);
        SearchResponse response = client.search(searchRequest);
        List<WatchRecord> records = new ArrayList<>();
        try {
            if (response.getTotalShards() != response.getSuccessfulShards()) {
                return null;
            }

            if (response.getHits().getTotalHits() > 0) {
                response = client.searchScroll(response.getScrollId(), scrollTimeout);
                while (response.getHits().hits().length != 0) {
                    for (SearchHit sh : response.getHits()) {
                        String id = sh.getId();
                        try {
                            WatchRecord record = recordParser.parse(id, sh.version(), sh.getSourceRef());
                            assert record.state() == recordState;
                            logger.debug("loaded watch record [{}/{}/{}]", sh.index(), sh.type(), sh.id());
                            records.add(record);
                        } catch (WatcherException we) {
                            logger.error("while loading records, failed to parse watch record [{}]", we, id);
                            throw we;
                        }
                    }
                    response = client.searchScroll(response.getScrollId(), scrollTimeout);
                }
            }
        } finally {
            client.clearScroll(response.getScrollId());
        }
        templateUtils.ensureIndexTemplateIsLoaded(state, INDEX_TEMPLATE_NAME);
        return records;
    }

    /**
     * Calculates the correct history index name for a given time
     */
    public static String getHistoryIndexNameForTime(DateTime time) {
        return INDEX_PREFIX + indexTimeFormat.print(time);
    }

    private SearchRequest createScanSearchRequest(WatchRecord.State recordState) {
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder()
                .query(QueryBuilders.termQuery(WatchRecord.Parser.STATE_FIELD.getPreferredName(), recordState.id()))
                .size(scrollSize)
                .version(true);

        SearchRequest searchRequest = new SearchRequest(INDEX_PREFIX + "*");
        searchRequest.source(sourceBuilder);
        searchRequest.searchType(SearchType.SCAN);
        searchRequest.types(DOC_TYPE);
        searchRequest.scroll(scrollTimeout);
        searchRequest.preference("_primary");
        return searchRequest;
    }
}
