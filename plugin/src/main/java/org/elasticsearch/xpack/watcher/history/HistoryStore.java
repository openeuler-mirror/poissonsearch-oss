/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.history;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.xpack.watcher.execution.ExecutionState;
import org.elasticsearch.xpack.watcher.support.WatcherIndexTemplateRegistry;
import org.elasticsearch.xpack.watcher.support.init.proxy.WatcherClientProxy;
import org.elasticsearch.xpack.watcher.support.xcontent.WatcherParams;
import org.elasticsearch.xpack.watcher.watch.WatchStoreUtils;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.elasticsearch.xpack.watcher.support.Exceptions.ioException;

public class HistoryStore extends AbstractComponent {

    public static final String INDEX_PREFIX = ".watcher-history-";
    public static final String INDEX_PREFIX_WITH_TEMPLATE = INDEX_PREFIX + WatcherIndexTemplateRegistry.INDEX_TEMPLATE_VERSION + "-";
    public static final String DOC_TYPE = "doc";

    static final DateTimeFormatter indexTimeFormat = DateTimeFormat.forPattern("YYYY.MM.dd");

    private final WatcherClientProxy client;

    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock putUpdateLock = readWriteLock.readLock();
    private final Lock stopLock = readWriteLock.writeLock();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public HistoryStore(Settings settings, WatcherClientProxy client) {
        super(settings);
        this.client = client;
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

    /**
     * Stores the specified watchRecord.
     * If the specified watchRecord already was stored this call will fail with a version conflict.
     */
    public void put(WatchRecord watchRecord) throws Exception {
        if (!started.get()) {
            throw new IllegalStateException("unable to persist watch record history store is not ready");
        }
        String index = getHistoryIndexNameForTime(watchRecord.triggerEvent().triggeredTime());
        putUpdateLock.lock();
        try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
            watchRecord.toXContent(builder, WatcherParams.builder().hideSecrets(true).build());

            IndexRequest request = new IndexRequest(index, DOC_TYPE, watchRecord.id().value())
                    .source(builder)
                    .opType(IndexRequest.OpType.CREATE);
            client.index(request, (TimeValue) null);
            logger.debug("indexed watch history record [{}]", watchRecord.id().value());
        } catch (IOException ioe) {
            throw ioException("failed to persist watch record [{}]", ioe, watchRecord);
        } finally {
            putUpdateLock.unlock();
        }
    }

    /**
     * Stores the specified watchRecord.
     * Any existing watchRecord will be overwritten.
     */
    public void forcePut(WatchRecord watchRecord) {
        if (!started.get()) {
            throw new IllegalStateException("unable to persist watch record history store is not ready");
        }
        String index = getHistoryIndexNameForTime(watchRecord.triggerEvent().triggeredTime());
        putUpdateLock.lock();
        try {
            try (XContentBuilder builder = XContentFactory.jsonBuilder()) {
                watchRecord.toXContent(builder, WatcherParams.builder().hideSecrets(true).build());

                IndexRequest request = new IndexRequest(index, DOC_TYPE, watchRecord.id().value())
                        .source(builder)
                        .opType(IndexRequest.OpType.CREATE);
                client.index(request, (TimeValue) null);
                logger.debug("indexed watch history record [{}]", watchRecord.id().value());
            } catch (VersionConflictEngineException vcee) {
                watchRecord = new WatchRecord.MessageWatchRecord(watchRecord, ExecutionState.EXECUTED_MULTIPLE_TIMES,
                        "watch record [{ " + watchRecord.id() + " }] has been stored before, previous state [" + watchRecord.state() + "]");
                try (XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()) {
                    IndexRequest request = new IndexRequest(index, DOC_TYPE, watchRecord.id().value())
                            .source(xContentBuilder.value(watchRecord));
                    client.index(request, (TimeValue) null);
                }
                logger.debug("overwrote watch history record [{}]", watchRecord.id().value());
            }
        } catch (IOException ioe) {
            final WatchRecord wr = watchRecord;
            logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to persist watch record [{}]", wr), ioe);
        } finally {
            putUpdateLock.unlock();
        }
    }

    /**
     * Calculates the correct history index name for a given time
     */
    public static String getHistoryIndexNameForTime(DateTime time) {
        return INDEX_PREFIX_WITH_TEMPLATE + indexTimeFormat.print(time);
    }

    /**
     * Check if everything is set up for the history store to operate fully. Checks for the
     * current watcher history index and if it is open.
     *
     * @param state The current cluster state
     * @return true, if history store is ready to be started
     */
    public static boolean validate(ClusterState state) {
        String currentIndex = getHistoryIndexNameForTime(DateTime.now(DateTimeZone.UTC));
        IndexMetaData indexMetaData = WatchStoreUtils.getConcreteIndex(currentIndex, state.metaData());
        if (indexMetaData == null) {
            return true;
        } else {
            return indexMetaData.getState() == IndexMetaData.State.OPEN &&
                    state.routingTable().index(indexMetaData.getIndex()).allPrimaryShardsActive();
        }
    }
}
