/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.actions;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.alerts.Alert;
import org.elasticsearch.alerts.AlertsStore;
import org.elasticsearch.alerts.LoadingListener;
import org.elasticsearch.alerts.plugin.AlertsPlugin;
import org.elasticsearch.alerts.triggers.TriggerManager;
import org.elasticsearch.alerts.triggers.TriggerResult;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.BytesStreamInput;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.*;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

/**
 */
public class AlertActionManager extends AbstractComponent {

    public static final String ALERT_NAME_FIELD = "alert_name";
    public static final String TRIGGERED_FIELD = "triggered";
    public static final String FIRE_TIME_FIELD = "fire_time";
    public static final String SCHEDULED_FIRE_TIME_FIELD = "scheduled_fire_time";
    public static final String TRIGGER_FIELD = "trigger";
    public static final String REQUEST = "request_binary";
    public static final String RESPONSE = "response_binary";
    public static final String ACTIONS_FIELD = "actions";

    public static final String ALERT_HISTORY_INDEX = "alerthistory";
    public static final String ALERT_HISTORY_TYPE = "alerthistory";

    private final Client client;
    private final ThreadPool threadPool;
    private final AlertsStore alertsStore;
    private final AlertActionRegistry actionRegistry;

    private final BlockingQueue<AlertActionEntry> actionsToBeProcessed = new LinkedBlockingQueue<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    private final int scrollSize;
    private final TimeValue scrollTimeout;

    private static AlertActionEntry END_ENTRY = new AlertActionEntry();

    @Inject
    public AlertActionManager(Settings settings, Client client, AlertActionRegistry actionRegistry, ThreadPool threadPool, AlertsStore alertsStore) {
        super(settings);
        this.client = client;
        this.actionRegistry = actionRegistry;
        this.threadPool = threadPool;
        this.alertsStore = alertsStore;
        // Not using component settings, to let AlertsStore and AlertActionManager share the same settings
        this.scrollSize = settings.getAsInt("alerts.scroll.size", 100);
        this.scrollTimeout = settings.getAsTime("alerts.scroll.timeout", TimeValue.timeValueSeconds(30));
    }

    public void start(ClusterState state, final LoadingListener listener) {
        IndexMetaData indexMetaData = state.getMetaData().index(ALERT_HISTORY_INDEX);
        if (indexMetaData != null) {
            if (state.routingTable().index(ALERT_HISTORY_INDEX).allPrimaryShardsActive()) {
                if (this.state.compareAndSet(State.STOPPED, State.LOADING)) {
                    threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                        @Override
                        public void run() {
                            boolean success = false;
                            try {
                                loadQueue();
                                success = true;
                            } catch (Exception e) {
                                logger.error("Unable to load unfinished jobs into the job queue", e);
                            } finally {
                                if (success) {
                                    if (AlertActionManager.this.state.compareAndSet(State.LOADING, State.STARTED)) {
                                        doStart();
                                        listener.onSuccess();
                                    }
                                } else {
                                    if (AlertActionManager.this.state.compareAndSet(State.LOADING, State.STOPPED)) {
                                        listener.onFailure();
                                    }
                                }
                            }
                        }
                    });
                }
            }
        } else {
            if (this.state.compareAndSet(State.STOPPED, State.STARTED)) {
                doStart();
                listener.onSuccess();
            }
        }
    }

    public void stop() {
        if (state.compareAndSet(State.STARTED, State.STOPPED)) {
            logger.info("Stopping job queue");
            actionsToBeProcessed.add(END_ENTRY);
        }
    }

    public boolean started() {
        return state.get() == State.STARTED;
    }

    private void doStart() {
        logger.info("Starting job queue");
        threadPool.executor(ThreadPool.Names.GENERIC).execute(new QueueReaderThread());
    }

    public void loadQueue() {
        SearchResponse response = client.prepareSearch()
                .setQuery(QueryBuilders.termQuery(AlertActionState.FIELD_NAME, AlertActionState.ACTION_NEEDED))
                .setSearchType(SearchType.SCAN)
                .setScroll(scrollTimeout)
                .setSize(scrollSize)
                .setTypes(ALERT_HISTORY_TYPE)
                .setIndices(ALERT_HISTORY_INDEX).get();
        try {
            while (response.getHits().hits().length != 0) {
                for (SearchHit sh : response.getHits()) {
                    String historyId = sh.getId();
                    AlertActionEntry historyEntry = parseHistory(historyId, sh.getSourceRef(), sh.version(), actionRegistry);
                    assert historyEntry.getEntryState() == AlertActionState.ACTION_NEEDED;
                    actionsToBeProcessed.add(historyEntry);
                }
                response = client.prepareSearchScroll(response.getScrollId()).setScroll(scrollTimeout).get();
            }
        } finally {
            client.prepareClearScroll().addScrollId(response.getScrollId()).get();
        }
        logger.info("Loaded [{}] actions from the alert history index into actions queue", actionsToBeProcessed.size());
    }

    static AlertActionEntry parseHistory(String historyId, BytesReference source, long version, AlertActionRegistry actionRegistry) {
        AlertActionEntry entry = new AlertActionEntry();
        entry.setId(historyId);
        entry.setVersion(version);
        try (XContentParser parser = XContentHelper.createParser(source)) {
            String currentFieldName = null;
            XContentParser.Token token = parser.nextToken();
            assert token == XContentParser.Token.START_OBJECT;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    switch (currentFieldName) {
                        case ACTIONS_FIELD:
                            entry.setActions(actionRegistry.instantiateAlertActions(parser));
                            break;
                        case TRIGGER_FIELD:
                            entry.setTrigger(TriggerManager.parseTrigger(parser));
                            break;
                        case "response":
                            // Ignore this, the binary form is already read
                            parser.skipChildren();
                            break;
                        default:
                            throw new ElasticsearchIllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                    }
                } else if (token.isValue()) {
                    switch (currentFieldName) {
                        case ALERT_NAME_FIELD:
                            entry.setAlertName(parser.text());
                            break;
                        case TRIGGERED_FIELD:
                            entry.setTriggered(parser.booleanValue());
                            break;
                        case FIRE_TIME_FIELD:
                            entry.setFireTime(DateTime.parse(parser.text()));
                            break;
                        case SCHEDULED_FIRE_TIME_FIELD:
                            entry.setScheduledTime(DateTime.parse(parser.text()));
                            break;
                        case REQUEST:
                            SearchRequest request = new SearchRequest();
                            request.readFrom(new BytesStreamInput(parser.binaryValue(), false));
                            entry.setSearchRequest(request);
                            break;
                        case RESPONSE:
                            SearchResponse response = new SearchResponse();
                            response.readFrom(new BytesStreamInput(parser.binaryValue(), false));
                            entry.setSearchResponse(response);
                            break;
                        case AlertActionState.FIELD_NAME:
                            entry.setEntryState(AlertActionState.fromString(parser.text()));
                            break;
                        default:
                            throw new ElasticsearchIllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                    }
                } else {
                    throw new ElasticsearchIllegalArgumentException("Unexpected token [" + token + "]");
                }
            }
        } catch (IOException e) {
            throw new ElasticsearchException("Error during parsing alert action", e);
        }
        return entry;
    }

    public void addAlertAction(Alert alert, TriggerResult result, DateTime scheduledFireTime, DateTime fireTime) throws IOException {
        AlertActionState state = AlertActionState.NO_ACTION_NEEDED;
        if (result.isTriggered() && !alert.actions().isEmpty()) {
            state = AlertActionState.ACTION_NEEDED;
        }

        AlertActionEntry entry = new AlertActionEntry(alert, result, scheduledFireTime, fireTime, state);
        IndexResponse response = client.prepareIndex(ALERT_HISTORY_INDEX, ALERT_HISTORY_TYPE, entry.getId())
                .setSource(XContentFactory.jsonBuilder().value(entry))
                .setOpType(IndexRequest.OpType.CREATE)
                .get();
        entry.setVersion(response.getVersion());
        if (state != AlertActionState.NO_ACTION_NEEDED) {
            actionsToBeProcessed.add(entry);
        }
    }

    private void updateHistoryEntry(AlertActionEntry entry, AlertActionState actionPerformed) throws IOException {
        entry.setEntryState(actionPerformed);
        UpdateRequest updateRequest = new UpdateRequest();
        updateRequest.index(ALERT_HISTORY_INDEX);
        updateRequest.type(ALERT_HISTORY_TYPE);
        updateRequest.id(entry.getId());
        IndexResponse response = client.prepareIndex(ALERT_HISTORY_INDEX, ALERT_HISTORY_TYPE, entry.getId())
                .setSource(XContentFactory.jsonBuilder().value(entry))
                .get();
        entry.setVersion(response.getVersion());
    }

    private class AlertHistoryRunnable implements Runnable {

        private final AlertActionEntry entry;

        private AlertHistoryRunnable(AlertActionEntry entry) {
            this.entry = entry;
        }

        @Override
        public void run() {
            try {
                Alert alert = alertsStore.getAlert(entry.getAlertName());
                updateHistoryEntry(entry, AlertActionState.ACTION_NEEDED);

                actionRegistry.doAction(alert, entry);

                alert.lastActionFire(entry.getFireTime());
                alertsStore.updateAlert(alert);
                updateHistoryEntry(entry, AlertActionState.ACTION_PERFORMED);
            } catch (Throwable t) {
                logger.error("Failed to execute alert action", t);
            }
        }
    }

    private class QueueReaderThread implements Runnable {

        @Override
        public void run() {
            try {
                logger.debug("Starting thread to read from the job queue");
                while (started()) {
                    AlertActionEntry entry = null;
                    do {
                        try {
                            entry = actionsToBeProcessed.take();
                        } catch (InterruptedException ie) {
                            if (!started()) {
                                break;
                            }
                        }
                    } while (entry == null);

                    if (!started() || entry == END_ENTRY) {
                        logger.debug("Stopping thread to read from the job queue");
                        return;
                    }
                    threadPool.executor(AlertsPlugin.THREAD_POOL_NAME).execute(new AlertHistoryRunnable(entry));
                }
            } catch (Exception e) {
                logger.error("Error during reader thread, restarting queue reader thread...", e);
                threadPool.executor(ThreadPool.Names.GENERIC).execute(new QueueReaderThread());
            }
        }
    }

    private enum State {

        STOPPED,
        LOADING,
        STARTED

    }

}
