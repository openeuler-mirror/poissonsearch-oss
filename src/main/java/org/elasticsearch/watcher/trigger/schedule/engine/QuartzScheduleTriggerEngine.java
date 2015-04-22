/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.trigger.schedule.engine;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.joda.time.DateTimeZone;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.EsThreadPoolExecutor;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.WatcherSettingsException;
import org.elasticsearch.watcher.support.ThreadPoolSettingsBuilder;
import org.elasticsearch.watcher.support.clock.Clock;
import org.elasticsearch.watcher.trigger.TriggerException;
import org.elasticsearch.watcher.trigger.schedule.*;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.simpl.SimpleJobFactory;

import java.util.*;

/**
 *
 */
public class QuartzScheduleTriggerEngine extends ScheduleTriggerEngine {

    public static final String THREAD_POOL_NAME = "watcher_scheduler";

    public static Settings additionalSettings(Settings nodeSettings) {
        Settings settings = nodeSettings.getAsSettings("threadpool." + THREAD_POOL_NAME);
        if (!settings.names().isEmpty()) {
            // scheduler TP is already configured in the node settings
            // no need for additional settings
            return ImmutableSettings.EMPTY;
        }
        int availableProcessors = EsExecutors.boundedNumberOfProcessors(settings);
        return new ThreadPoolSettingsBuilder.Fixed(THREAD_POOL_NAME)
                .size(availableProcessors)
                .queueSize(1000)
                .build();
    }

    private final Clock clock;
    private final DateTimeZone defaultTimeZone;

    private final EsThreadPoolExecutor executor;
    private volatile org.quartz.Scheduler scheduler;

    @Inject
    public QuartzScheduleTriggerEngine(Settings settings, ScheduleRegistry scheduleRegistry, ThreadPool threadPool, Clock clock) {
        super(settings, scheduleRegistry);
        this.executor = (EsThreadPoolExecutor) threadPool.executor(THREAD_POOL_NAME);
        this.clock = clock;
        String timeZoneStr = componentSettings.get("time_zone", "UTC");
        try {
            this.defaultTimeZone = DateTimeZone.forID(timeZoneStr);
        } catch (IllegalArgumentException iae) {
            throw new WatcherSettingsException("unrecognized time zone setting [" + timeZoneStr + "]", iae);
        }
    }

    @Override
    public String type() {
        return ScheduleTrigger.TYPE;
    }

    @Override
    public void start(Collection<Job> jobs) {
        try {
            logger.info("Starting scheduler");
            // Can't start a scheduler that has been shutdown, so we need to re-create each time start() is invoked
            Properties properties = new Properties();
            properties.setProperty("org.quartz.threadPool.class", WatcherQuartzThreadPool.class.getName());
            properties.setProperty(StdSchedulerFactory.PROP_SCHED_SKIP_UPDATE_CHECK, "true");
            properties.setProperty(StdSchedulerFactory.PROP_SCHED_INTERRUPT_JOBS_ON_SHUTDOWN, "true");
            properties.setProperty(StdSchedulerFactory.PROP_SCHED_INTERRUPT_JOBS_ON_SHUTDOWN_WITH_WAIT, "true");
            SchedulerFactory schFactory = new StdSchedulerFactory(properties);
            scheduler = schFactory.getScheduler();
            scheduler.setJobFactory(new SimpleJobFactory());
            Map<JobDetail, Set<? extends Trigger>> quartzJobs = new HashMap<>();
            for (Job job : jobs) {
                if (job.trigger() instanceof ScheduleTrigger) {
                    ScheduleTrigger trigger = (ScheduleTrigger) job.trigger();
                    quartzJobs.put(WatcherQuartzJob.jobDetail(job.name(), this), createTrigger(trigger.schedule(), defaultTimeZone, clock));
                }
            }
            scheduler.scheduleJobs(quartzJobs, false);
            scheduler.start();
        } catch (org.quartz.SchedulerException se) {
            logger.error("Failed to start quartz scheduler", se);
        }
    }

    @Override
    public void stop() {
        try {
            org.quartz.Scheduler scheduler = this.scheduler;
            if (scheduler != null) {
                logger.info("Stopping scheduler...");
                scheduler.shutdown(true);
                this.scheduler = null;
                executor.getQueue().clear();
                logger.info("Stopped scheduler");
            }
        } catch (org.quartz.SchedulerException se){
            logger.error("Failed to stop quartz scheduler", se);
        }
    }

    @Override
    public void add(Job job) {
        assert job.trigger() instanceof ScheduleTrigger;
        ScheduleTrigger trigger = (ScheduleTrigger) job.trigger();
        try {
            logger.trace("scheduling [{}] with schedule [{}]", job.name(), trigger.schedule());
            scheduler.scheduleJob(WatcherQuartzJob.jobDetail(job.name(), this), createTrigger(trigger.schedule(), defaultTimeZone, clock), true);
        } catch (org.quartz.SchedulerException se) {
            logger.error("failed to schedule job",se);
            throw new TriggerException("failed to schedule job", se);
        }
    }

    @Override
    public boolean remove(String jobName) {
        try {
            return scheduler.deleteJob(new JobKey(jobName));
        } catch (org.quartz.SchedulerException se){
            throw new TriggerException("failed to remove [" + jobName + "] from the scheduler", se);
        }
    }


    static Set<Trigger> createTrigger(Schedule schedule, DateTimeZone timeZone, Clock clock) {
        HashSet<Trigger> triggers = new HashSet<>();
        if (schedule instanceof CronnableSchedule) {
            for (Cron cron : ((CronnableSchedule) schedule).crons()) {
                triggers.add(TriggerBuilder.newTrigger()
                        .withSchedule(CronScheduleBuilder.cronSchedule(cron.expression()).inTimeZone(timeZone.toTimeZone()))
                        .startAt(clock.now().toDate())
                        .build());
            }
        } else {
            // must be interval schedule
            IntervalSchedule.Interval interval = ((IntervalSchedule) schedule).interval();
            triggers.add(TriggerBuilder.newTrigger().withSchedule(SimpleScheduleBuilder.simpleSchedule()
                    .withIntervalInSeconds((int) interval.seconds())
                    .repeatForever())
                    .startAt(clock.now().toDate())
                    .build());
        }
        return triggers;
    }

    void notifyListeners(String name, JobExecutionContext ctx) {
        ScheduleTriggerEvent event = new ScheduleTriggerEvent(new DateTime(ctx.getFireTime()), new DateTime(ctx.getScheduledFireTime()));
        for (Listener listener : listeners) {
            executor.execute(new ListenerRunnable(listener, name, event));
        }
    }

    static class ListenerRunnable implements Runnable {

        private final Listener listener;
        private final String jobName;
        private final ScheduleTriggerEvent event;

        public ListenerRunnable(Listener listener, String jobName, ScheduleTriggerEvent event) {
            this.listener = listener;
            this.jobName = jobName;
            this.event = event;
        }

        @Override
        public void run() {
            listener.triggered(jobName, event);
        }
    }

    public static final class WatcherQuartzThreadPool implements org.quartz.spi.ThreadPool {

        @Override
        public boolean runInThread(Runnable runnable) {
            runnable.run();
            return true;
        }

        @Override
        public int blockForAvailableThreads() {
            return 1;
        }

        @Override
        public void initialize() throws SchedulerConfigException {

        }

        @Override
        public void shutdown(boolean waitForJobsToComplete) {

        }

        @Override
        public int getPoolSize() {
            return 1;
        }

        @Override
        public void setInstanceId(String schedInstId) {
        }

        @Override
        public void setInstanceName(String schedName) {
        }
    }
}
