/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.history;

import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.cluster.settings.DynamicSettings;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.execution.ExecutionState;
import org.elasticsearch.watcher.execution.Wid;
import org.elasticsearch.watcher.support.TemplateUtils;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.elasticsearch.watcher.test.WatcherMatchers.indexRequest;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.Mockito.*;

/**
 */
public class HistoryStoreTests extends ElasticsearchTestCase {

    private HistoryStore historyStore;
    private ClientProxy clientProxy;

    @Before
    public void init() {
        clientProxy = mock(ClientProxy.class);
        TemplateUtils templateUtils = mock(TemplateUtils.class);
        NodeSettingsService nodeSettingsService = mock(NodeSettingsService.class);
        DynamicSettings dynamicSettings = mock(DynamicSettings.class);
        ThreadPool threadPool = mock(ThreadPool.class);
        historyStore = new HistoryStore(ImmutableSettings.EMPTY, clientProxy, templateUtils, nodeSettingsService, dynamicSettings, threadPool);
        historyStore.start();
    }

    @Test
    public void testPut() throws Exception {
        Wid wid = new Wid("_name", 0, new DateTime(0, UTC));
        ScheduleTriggerEvent event = new ScheduleTriggerEvent(wid.watchId(), new DateTime(0, UTC), new DateTime(0, UTC));
        WatchRecord watchRecord = new WatchRecord(wid, event, null, ExecutionState.EXECUTED);

        IndexResponse indexResponse = mock(IndexResponse.class);
        IndexRequest indexRequest = indexRequest(".watch_history-1970.01.01", HistoryStore.DOC_TYPE, wid.value(), IndexRequest.OpType.CREATE);
        when(clientProxy.index(indexRequest)).thenReturn(indexResponse);
        historyStore.put(watchRecord);
        verify(clientProxy).index(Matchers.<IndexRequest>any());
    }

    @Test(expected = HistoryException.class)
    public void testPut_stopped() {
        Wid wid = new Wid("_name", 0, new DateTime(0, UTC));
        ScheduleTriggerEvent event = new ScheduleTriggerEvent(wid.watchId(), new DateTime(0, UTC), new DateTime(0, UTC));
        WatchRecord watchRecord = new WatchRecord(wid, event, null, ExecutionState.EXECUTED);

        historyStore.stop();
        try {
            historyStore.put(watchRecord);
        } finally {
            historyStore.start();
        }
        fail();
    }

    @Test
    public void testIndexNameGeneration() {
        assertThat(HistoryStore.getHistoryIndexNameForTime(new DateTime(0, UTC)), equalTo(".watch_history-1970.01.01"));
        assertThat(HistoryStore.getHistoryIndexNameForTime(new DateTime(100000000000L, UTC)), equalTo(".watch_history-1973.03.03"));
        assertThat(HistoryStore.getHistoryIndexNameForTime(new DateTime(1416582852000L, UTC)), equalTo(".watch_history-2014.11.21"));
        assertThat(HistoryStore.getHistoryIndexNameForTime(new DateTime(2833165811000L, UTC)), equalTo(".watch_history-2059.10.12"));
    }

}
