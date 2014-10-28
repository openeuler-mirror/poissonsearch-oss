/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.scheduler;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.alerts.Alert;
import org.elasticsearch.alerts.actions.AlertActionManager;
import org.elasticsearch.alerts.AlertManager;
import org.elasticsearch.alerts.triggers.TriggerManager;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.index.query.FilteredQueryBuilder;
import org.elasticsearch.index.query.RangeFilterBuilder;
import org.elasticsearch.index.query.TemplateQueryBuilder;
import org.elasticsearch.script.ExecutableScript;
import org.elasticsearch.script.ScriptService;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.simpl.SimpleJobFactory;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class AlertScheduler extends AbstractLifecycleComponent implements ClusterStateListener {

    private final Client client;
    private final Scheduler scheduler;
    private final AlertManager alertManager;
    private final TriggerManager triggerManager;
    private final ScriptService scriptService;

    private AlertActionManager actionManager;


    private final AtomicBoolean run = new AtomicBoolean(false);

    @Inject
    public AlertScheduler(Settings settings, AlertManager alertManager, Client client,
                          TriggerManager triggerManager, ScriptService scriptService,
                          ClusterService clusterService) {
        super(settings);
        this.alertManager = alertManager;
        this.client = client;
        this.triggerManager = triggerManager;
        this.scriptService = scriptService;
        try {
            SchedulerFactory schFactory = new StdSchedulerFactory();
            scheduler = schFactory.getScheduler();
            scheduler.setJobFactory(new SimpleJobFactory());
        } catch (SchedulerException e) {
            throw new ElasticsearchException("Failed to instantiate scheduler", e);
        }
        clusterService.add(this);
        alertManager.setAlertScheduler(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.state().nodes().localNodeMaster()) {
            if (run.compareAndSet(false, true)) {
                try {
                    logger.info("Starting scheduler");
                    scheduler.start();
                } catch (SchedulerException se){
                    logger.error("Failed to start quartz scheduler", se);
                }
            }
        } else {
            stopIfRunning();
        }
    }

    private void stopIfRunning() {
        if (run.compareAndSet(true, false)) {
            try {
                logger.info("Stopping scheduler");
                if (!scheduler.isShutdown()) {
                    scheduler.clear();
                    scheduler.shutdown(false);
                }
            } catch (SchedulerException se){
                logger.error("Failed to stop quartz scheduler", se);
            }
        }
    }

    @Override
    protected void doStart() throws ElasticsearchException {
    }

    @Override
    protected void doStop() throws ElasticsearchException {
        stopIfRunning();
    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    public boolean deleteAlertFromSchedule(String alertName) {
        try {
            return scheduler.deleteJob(new JobKey(alertName));
        } catch (SchedulerException se){
            throw new ElasticsearchException("Failed to remove [" + alertName + "] from the scheduler", se);
        }
    }

    public void clearAlerts() {
        try {
            scheduler.clear();
        } catch (SchedulerException se){
            throw new ElasticsearchException("Failed to clear scheduler", se);
        }
    }

    public void executeAlert(String alertName, JobExecutionContext jobExecutionContext){
        logger.warn("Running [{}]",alertName);
        Alert alert = alertManager.getAlertForName(alertName);
        DateTime scheduledTime =  new DateTime(jobExecutionContext.getScheduledFireTime());
        if (!alert.enabled()) {
            logger.warn("Alert [{}] is not enabled", alertName);
            return;
        }
        try {
            if (!alertManager.claimAlertRun(alertName, scheduledTime) ){
                logger.warn("Another process has already run this alert.");
                return;
            }
            alert = alertManager.getAlertForName(alertName); //The claim may have triggered a refresh

            SearchRequestBuilder srb = createClampedRequest(client, jobExecutionContext, alert);
            String[] indices = alert.indices().toArray(new String[0]);

            if (alert.indices() != null ){
                logger.warn("Setting indices to : " + alert.indices());
                srb.setIndices(indices);
            }

            //if (logger.isDebugEnabled()) {
            logger.warn("Running query [{}]", XContentHelper.convertToJson(srb.request().source(), false, true));
            //}

            SearchResponse sr = srb.execute().get();
            logger.warn("Got search response hits : [{}]", sr.getHits().getTotalHits() );

            boolean isTriggered = triggerManager.isTriggered(alertName,sr);

            alertManager.updateLastRan(alertName, new DateTime(jobExecutionContext.getFireTime()),scheduledTime);
            if (!alertManager.addHistory(alertName, isTriggered,
                    new DateTime(jobExecutionContext.getScheduledFireTime()), scheduledTime, srb,
                    alert.trigger(), sr.getHits().getTotalHits(), alert.actions(), alert.indices()))
            {
                logger.warn("Failed to store history for alert [{}]", alertName);
            }

        } catch (Exception e) {
            logger.error("Failed execute alert [{}]", e, alertName);
        }
    }

    private SearchRequestBuilder createClampedRequest(Client client, JobExecutionContext jobExecutionContext, Alert alert){
        Date scheduledFireTime = jobExecutionContext.getScheduledFireTime();
        DateTime clampEnd = new DateTime(scheduledFireTime);
        DateTime clampStart = clampEnd.minusSeconds((int)alert.timePeriod().seconds());
        if (alert.simpleQuery()) {
            TemplateQueryBuilder queryBuilder = new TemplateQueryBuilder(alert.queryName(), ScriptService.ScriptType.INDEXED, new HashMap<String, Object>());
            RangeFilterBuilder filterBuilder = new RangeFilterBuilder(alert.timestampString());
            filterBuilder.gte(clampStart);
            filterBuilder.lt(clampEnd);
            return client.prepareSearch().setQuery(new FilteredQueryBuilder(queryBuilder, filterBuilder));
        } else {
            //We can't just wrap the template here since it probably contains aggs or something else that doesn't play nice with FilteredQuery
            Map<String,Object> fromToMap = new HashMap<>();
            fromToMap.put("from", clampStart); //@TODO : make these parameters configurable ? Don't want to bloat the API too much tho
            fromToMap.put("to", clampEnd);
            //Go and get the search template from the script service :(
            ExecutableScript script =  scriptService.executable("mustache", alert.queryName(), ScriptService.ScriptType.INDEXED, fromToMap);
            BytesReference requestBytes = (BytesReference)(script.run());
            return client.prepareSearch().setSource(requestBytes);
        }
    }

    public void addAlert(String alertName, Alert alert) {
        JobDetail job = JobBuilder.newJob(AlertExecutorJob.class).withIdentity(alertName).build();
        job.getJobDataMap().put("manager",this);
        CronTrigger cronTrigger = TriggerBuilder.newTrigger()
                .withSchedule(CronScheduleBuilder.cronSchedule(alert.schedule()))
                .build();
        try {
            logger.warn("Scheduling [{}] with schedule [{}]", alertName, alert.schedule());
            scheduler.scheduleJob(job, cronTrigger);
        } catch (SchedulerException se) {
            logger.error("Failed to schedule job",se);
        }
    }

    public boolean isRunning() {
        return true;
    }

}
