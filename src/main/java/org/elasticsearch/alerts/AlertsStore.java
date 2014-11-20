/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.alerts.actions.AlertAction;
import org.elasticsearch.alerts.actions.AlertActionRegistry;
import org.elasticsearch.alerts.triggers.TriggerManager;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.lucene.uid.Versions;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;

/**
 */
public class AlertsStore extends AbstractComponent {

    public static final String ALERT_INDEX = ".alerts";
    public static final String ALERT_TYPE = "alert";

    public static final ParseField SCHEDULE_FIELD = new ParseField("schedule");
    public static final ParseField TRIGGER_FIELD = new ParseField("trigger");
    public static final ParseField ACTION_FIELD = new ParseField("actions");
    public static final ParseField LAST_ACTION_FIRE = new ParseField("last_action_fire");
    public static final ParseField ENABLE = new ParseField("enable");
    public static final ParseField REQUEST_FIELD = new ParseField("request");
    public static final ParseField THROTTLE_PERIOD_FIELD = new ParseField("throttle_period");
    public static final ParseField LAST_ACTION_EXECUTED_FIELD = new ParseField("last_action_executed");
    public static final ParseField ACK_STATE_FIELD = new ParseField("ack_state");

    private final Client client;
    private final ThreadPool threadPool;
    private final ConcurrentMap<String, Alert> alertMap;
    private final AlertActionRegistry alertActionRegistry;
    private final TriggerManager triggerManager;
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final TemplateHelper templateHelper;

    private final int scrollSize;
    private final TimeValue scrollTimeout;

    @Inject
    public AlertsStore(Settings settings, Client client, ThreadPool threadPool, AlertActionRegistry alertActionRegistry,
                       TriggerManager triggerManager, TemplateHelper templateHelper) {
        super(settings);
        this.client = client;
        this.threadPool = threadPool;
        this.alertActionRegistry = alertActionRegistry;
        this.templateHelper = templateHelper;
        this.alertMap = ConcurrentCollections.newConcurrentMap();
        // Not using component settings, to let AlertsStore and AlertActionManager share the same settings
        this.scrollSize = settings.getAsInt("alerts.scroll.size", 100);
        this.scrollTimeout = settings.getAsTime("alerts.scroll.timeout", TimeValue.timeValueSeconds(30));
        this.triggerManager = triggerManager;
    }

    /**
     * Returns the alert with the specified name otherwise <code>null</code> is returned.
     */
    public Alert getAlert(String name) {
        return alertMap.get(name);
    }

    /**
     * Creates an alert with the specified name and source. If an alert with the specified name already exists it will
     * get overwritten.
     */
    public Tuple<Alert, IndexResponse> addAlert(String name, BytesReference alertSource) {
        Alert alert = parseAlert(name, alertSource);
        IndexResponse response = persistAlert(name, alertSource, IndexRequest.OpType.CREATE);
        alert.version(response.getVersion());
        alertMap.put(name, alert);
        return new Tuple<>(alert, response);
    }

    /**
     * Updates the specified alert by making sure that the made changes are persisted.
     */
    public IndexResponse updateAlert(Alert alert) throws IOException {
        IndexResponse response = client.prepareIndex(ALERT_INDEX, ALERT_TYPE, alert.alertName())
                .setSource(jsonBuilder().value(alert)) // TODO: the content type should be based on the provided content type when the alert was initially added.
                .setVersion(alert.version())
                .setOpType(IndexRequest.OpType.INDEX)
                .get();
        alert.version(response.getVersion());

        // Don'<></> need to update the alertMap, since we are working on an instance from it.
        assert alertMap.get(alert.alertName()) == alert;

        return response;
    }

    /**
     * Deletes the alert with the specified name if exists
     */
    public DeleteResponse deleteAlert(String name) {
        Alert alert = alertMap.remove(name);
        if (alert == null) {
            return new DeleteResponse(ALERT_INDEX, ALERT_TYPE, name, Versions.MATCH_ANY, false);
        }

        DeleteResponse deleteResponse = client.prepareDelete(ALERT_INDEX, ALERT_TYPE, name)
                .setVersion(alert.version())
                .get();
        assert deleteResponse.isFound();
        return deleteResponse;
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

    public void start(final ClusterState state, final LoadingListener listener) {
        IndexMetaData alertIndexMetaData = state.getMetaData().index(ALERT_INDEX);
        if (alertIndexMetaData != null) {
            logger.info("Previous alerting index");
            if (state.routingTable().index(ALERT_INDEX).allPrimaryShardsActive()) {
                logger.info("Previous alerting index with active primary shards");
                if (this.state.compareAndSet(State.STOPPED, State.LOADING)) {
                    logger.info("Started loading");
                    threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                        @Override
                        public void run() {
                            boolean success = false;
                            try {
                                templateHelper.checkAndUploadIndexTemplate(state, "alerts");
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
            if (AlertsStore.this.state.compareAndSet(State.STOPPED, State.LOADING)) {
                logger.info("No previous .alert index");
                threadPool.executor(ThreadPool.Names.GENERIC).execute(new Runnable() {
                    @Override
                    public void run() {
                        templateHelper.checkAndUploadIndexTemplate(state, "alerts");
                        if (AlertsStore.this.state.compareAndSet(State.LOADING, State.STARTED)) {
                            listener.onSuccess();
                        }
                    }
                });
            }
        }
    }

    public boolean started() {
        return state.get() == State.STARTED;
    }

    public void stop() {
        state.set(State.STOPPED);
        clear();
        logger.info("Stopped alert store");
    }

    private IndexResponse persistAlert(String alertName, BytesReference alertSource, IndexRequest.OpType opType) {
        IndexRequest indexRequest = new IndexRequest(ALERT_INDEX, ALERT_TYPE, alertName);
        indexRequest.listenerThreaded(false);
        indexRequest.source(alertSource, false);
        indexRequest.opType(opType);
        return client.index(indexRequest).actionGet();
    }

    private void loadAlerts() {
        client.admin().indices().refresh(new RefreshRequest(ALERT_INDEX)).actionGet();

        SearchResponse response = client.prepareSearch(ALERT_INDEX)
                .setTypes(ALERT_TYPE)
                .setSearchType(SearchType.SCAN)
                .setScroll(scrollTimeout)
                .setSize(scrollSize)
                .setVersion(true)
                .get();
        try {
            if (response.getHits().getTotalHits() > 0) {
                response = client.prepareSearchScroll(response.getScrollId()).setScroll(scrollTimeout).get();
                while (response.getHits().hits().length != 0) {
                    for (SearchHit sh : response.getHits()) {
                        String alertId = sh.getId();
                        Alert alert = parseAlert(alertId, sh);
                        alertMap.put(alertId, alert);
                    }
                    response = client.prepareSearchScroll(response.getScrollId()).setScroll(scrollTimeout).get();
                }
            }
        } finally {
            client.prepareClearScroll().addScrollId(response.getScrollId()).get();
        }
        logger.info("Loaded [{}] alerts from the alert index.", alertMap.size());
    }

    private Alert parseAlert(String alertId, SearchHit sh) {
        Alert alert = parseAlert(alertId, sh.getSourceRef());
        alert.version(sh.version());
        return alert;
    }

    protected Alert parseAlert(String alertName, BytesReference source) {
        Alert alert = new Alert();
        alert.alertName(alertName);
        logger.error("Source : [{}]", source.toUtf8());
        try (XContentParser parser = XContentHelper.createParser(source)) {
            String currentFieldName = null;
            XContentParser.Token token = parser.nextToken();
            assert token == XContentParser.Token.START_OBJECT;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (TRIGGER_FIELD.match(currentFieldName)) {
                        alert.trigger(triggerManager.instantiateAlertTrigger(parser));
                    } else if (ACTION_FIELD.match(currentFieldName)) {
                        List<AlertAction> actions = alertActionRegistry.instantiateAlertActions(parser);
                        alert.actions(actions);
                    } else if (REQUEST_FIELD.match(currentFieldName)) {
                        alert.setSearchRequest(AlertUtils.readSearchRequest(parser));
                    } else {
                        throw new ElasticsearchIllegalArgumentException("Unexpected field [" + currentFieldName + "]");
                    }
                } else if (token.isValue()) {
                    if (SCHEDULE_FIELD.match(currentFieldName)) {
                        alert.schedule(parser.textOrNull());
                    } else if (ENABLE.match(currentFieldName)) {
                        alert.enabled(parser.booleanValue());
                    } else if (LAST_ACTION_FIRE.match(currentFieldName)) {
                        alert.lastActionFire(DateTime.parse(parser.textOrNull()));
                    } else if (LAST_ACTION_EXECUTED_FIELD.match(currentFieldName)) {
                        alert.setTimeLastActionExecuted(DateTime.parse(parser.textOrNull()));
                    } else if (THROTTLE_PERIOD_FIELD.match(currentFieldName)) {
                        alert.setThrottlePeriod(TimeValue.parseTimeValue(parser.textOrNull(), new TimeValue(0)));
                    } else if (ACK_STATE_FIELD.match(currentFieldName)) {
                        alert.setAckState(AlertAckState.fromString(parser.textOrNull()));
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

        if (alert.schedule() == null) {
            throw new ElasticsearchIllegalArgumentException("Schedule is a required field");
        }

        if (alert.trigger() == null) {
            throw new ElasticsearchIllegalArgumentException("Trigger is a required field");
        }

        return alert;
    }

    private enum State {

        STOPPED,
        LOADING,
        STARTED

    }

}
