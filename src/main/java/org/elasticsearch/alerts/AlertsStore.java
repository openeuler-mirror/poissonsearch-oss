/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.alerts.actions.AlertAction;
import org.elasticsearch.alerts.actions.AlertActionRegistry;
import org.elasticsearch.alerts.transport.actions.delete.DeleteAlertResponse;
import org.elasticsearch.alerts.triggers.TriggerManager;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.BytesStreamInput;
import org.elasticsearch.common.io.stream.DataOutputStreamOutput;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.xcontent.*;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 */
public class AlertsStore extends AbstractComponent {

    public static final String ALERT_INDEX = ".alerts";
    public static final String ALERT_TYPE = "alert";

    public static final ParseField SCHEDULE_FIELD =  new ParseField("schedule");
    public static final ParseField TRIGGER_FIELD = new ParseField("trigger");
    public static final ParseField ACTION_FIELD = new ParseField("action");
    public static final ParseField LAST_ACTION_FIRE = new ParseField("last_action_fire");
    public static final ParseField ENABLED = new ParseField("enabled");
    public static final ParseField SEARCH_REQUEST_FIELD =  new ParseField("request");

    private final Client client;
    private final ThreadPool threadPool;
    private final ConcurrentMap<String,Alert> alertMap;
    private final AlertActionRegistry alertActionRegistry;
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    private final int scrollSize;
    private final TimeValue scrollTimeout;

    @Inject
    public AlertsStore(Settings settings, Client client, ThreadPool threadPool, AlertActionRegistry alertActionRegistry) {
        super(settings);
        this.client = client;
        this.threadPool = threadPool;
        this.alertActionRegistry = alertActionRegistry;
        this.alertMap = ConcurrentCollections.newConcurrentMap();
        this.scrollSize = componentSettings.getAsInt("scroll.size", 100);
        this.scrollTimeout = componentSettings.getAsTime("scroll.timeout", TimeValue.timeValueSeconds(30));
    }

    /**
     * Returns whether an alert with the specified name exists.
     */
    public boolean hasAlert(String name) {
        return alertMap.containsKey(name);
    }

    /**
     * Returns the alert with the specified name otherwise <code>null</code> is returned.
     */
    public Alert getAlert(String name) {
        return alertMap.get(name);
    }

    /**
     * Creates an alert with the specified and fails if an alert with the name already exists.
     */
    public Alert createAlert(String name, BytesReference alertSource) {
        Alert alert = parseAlert(name, alertSource, 1);
        if (alertMap.putIfAbsent(name, alert) == null) {
            persistAlert(name, alertSource, IndexRequest.OpType.CREATE);
        } else {
            throw new ElasticsearchIllegalArgumentException("There is already an alert named [" + name + "]");
        }
        return alert;
    }

    /**
     * Creates an alert with the specified and fails if an alert with the name already exists.
     */
    public IndexResponse createAlert(Alert alert) throws IOException {
        if (alertMap.putIfAbsent(alert.alertName(), alert) == null) {
            XContentBuilder jsonBuilder = XContentFactory.jsonBuilder();
            alert.toXContent(jsonBuilder, ToXContent.EMPTY_PARAMS);
            return persistAlert(alert.alertName(), jsonBuilder.bytes(), IndexRequest.OpType.CREATE);
        } else {
            throw new ElasticsearchIllegalArgumentException("There is already an alert named [" + alert.alertName() + "]");
        }
    }

    public IndexResponse updateAlert(Alert alert) {
        return updateAlert(alert, false);
    }

    /**
     * Updates the specified alert by making sure that the made changes are persisted.
     */
    public IndexResponse updateAlert(Alert alert, boolean updateMap) {
        IndexRequest updateRequest = new IndexRequest();
        updateRequest.index(ALERT_INDEX);
        updateRequest.type(ALERT_TYPE);
        updateRequest.id(alert.alertName());
        updateRequest.version(alert.version());
        XContentBuilder alertBuilder;
        try {
            alertBuilder = XContentFactory.jsonBuilder();
            alert.toXContent(alertBuilder, ToXContent.EMPTY_PARAMS);
        } catch (IOException ie) {
            throw new ElasticsearchException("Unable to serialize alert ["+ alert.alertName() + "]", ie);
        }
        updateRequest.source(alertBuilder);

        IndexResponse response = client.index(updateRequest).actionGet();
        alert.version(response.getVersion());

        if (updateMap) {
            alertMap.put(alert.alertName(), alert);
        } else {
            // Don'<></> need to update the alertMap, since we are working on an instance from it.
            assert alertMap.get(alert.alertName()) == alert;
        }
        return response;
    }

    /**
     * Deletes the alert with the specified name if exists
     */
    public DeleteResponse deleteAlert(String name) {
        Alert alert = alertMap.remove(name);
        if (alert == null) {
            return null;
        }
        DeleteRequest deleteRequest = new DeleteRequest();
        deleteRequest.id(name);
        deleteRequest.index(ALERT_INDEX);
        deleteRequest.type(ALERT_TYPE);
        deleteRequest.version(alert.version());
        DeleteResponse deleteResponse = client.delete(deleteRequest).actionGet();
        assert deleteResponse.isFound();
        return deleteResponse;
    }

    /**
     * Clears the in-memory representation of the alerts and loads the alerts from the .alerts index.
     */
    public void reload() {
        clear();
        loadAlerts();
    }

    /**
     * Clears the in-memory representation of the alerts
     */
    public void clear() {
        alertMap.clear();
    }

    public ConcurrentMap<String, Alert> getAlerts() {
        return alertMap;
    }

    public void start(ClusterState state, final LoadingListener listener) {
        IndexMetaData alertIndexMetaData = state.getMetaData().index(ALERT_INDEX);
        if (alertIndexMetaData != null) {
            if (state.routingTable().index(ALERT_INDEX).allPrimaryShardsActive()) {
                if (this.state.compareAndSet(State.STOPPED, State.LOADING)) {
                    threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                        @Override
                        public void run() {
                            boolean success = false;
                            try {
                                loadAlerts();
                                success = true;
                            } catch (Exception e) {
                                logger.warn("Failed to load alerts", e);
                            } finally {
                                if (success) {
                                    if (AlertsStore.this.state.compareAndSet(State.LOADING, State.STARTED)) {
                                        listener.onSuccess();
                                    }
                                } else {
                                    if (AlertsStore.this.state.compareAndSet(State.LOADING, State.STOPPED)) {
                                        listener.onFailure();
                                    }
                                }
                            }
                        }
                    });
                }
            }
        } else {
            if (AlertsStore.this.state.compareAndSet(State.STOPPED, State.STARTED)) {
                listener.onSuccess();
            }
        }
    }

    public boolean started() {
        return state.get() == State.STARTED;
    }

    public void stop() {
        state.set(State.STOPPED);
        clear();
    }

    private IndexResponse persistAlert(String alertName, BytesReference alertSource, IndexRequest.OpType opType) {
        IndexRequest indexRequest = new IndexRequest(ALERT_INDEX, ALERT_TYPE, alertName);
        indexRequest.listenerThreaded(false);
        indexRequest.source(alertSource, false);
        indexRequest.opType(opType);
        return client.index(indexRequest).actionGet();
    }

    private void loadAlerts() {
        SearchResponse response = client.prepareSearch()
                .setSearchType(SearchType.SCAN)
                .setScroll(scrollTimeout)
                .setSize(scrollSize)
                .setTypes(ALERT_TYPE)
                .setIndices(ALERT_INDEX).get();
        try {
            while (response.getHits().hits().length != 0) {
                for (SearchHit sh : response.getHits()) {
                    String alertId = sh.getId();
                    Alert alert = parseAlert(alertId, sh);
                    alertMap.put(alertId, alert);
                }
                response = client.prepareSearchScroll(response.getScrollId()).setScroll(scrollTimeout).get();
            }
        } finally {
            client.prepareClearScroll().addScrollId(response.getScrollId()).get();
        }
        logger.info("Loaded [{}] alerts from the alert index.", alertMap.size());
    }

    private Alert parseAlert(String alertId, SearchHit sh) {
        return parseAlert(alertId, sh.getSourceRef(), sh.getVersion());
    }

    private Alert parseAlert(String alertName, BytesReference source, long version) {
        Alert alert = new Alert();
        alert.alertName(alertName);
        alert.version(version);
        try (XContentParser parser = XContentHelper.createParser(source)) {
            String currentFieldName = null;
            XContentParser.Token token = parser.nextToken();
            assert token == XContentParser.Token.START_OBJECT;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (TRIGGER_FIELD.match(currentFieldName)) {
                        alert.trigger(TriggerManager.parseTrigger(parser));
                    } else if (ACTION_FIELD.match(currentFieldName)) {
                        List<AlertAction> actions = alertActionRegistry.instantiateAlertActions(parser);
                        alert.actions(actions);
                    } else {
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                    }
                } else if (token.isValue()) {
                    if (SCHEDULE_FIELD.match(currentFieldName)) {
                        alert.schedule(parser.textOrNull());
                    } else if (ENABLED.match(currentFieldName)) {
                        alert.enabled(parser.booleanValue());
                    } else if (LAST_ACTION_FIRE.match(currentFieldName)) {
                        alert.lastActionFire(DateTime.parse(parser.textOrNull()));
                    } else if (SEARCH_REQUEST_FIELD.match(currentFieldName)) {
                        SearchRequest searchRequest = new SearchRequest();
                        searchRequest.readFrom(new BytesStreamInput(parser.binaryValue(), false));
                        alert.setSearchRequest(searchRequest);
                    } else {
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                    }
                } else {
                    throw new ElasticsearchIllegalArgumentException("Unexpected token [" + token + "]");
                }
            }
        } catch (IOException e) {
            throw new ElasticsearchException("Error during parsing alert", e);
        }

        if (alert.lastActionFire() == null) {
            alert.lastActionFire(new DateTime(0));
        }
        return alert;
    }

    private enum State {

        STOPPED,
        LOADING,
        STARTED

    }

}
