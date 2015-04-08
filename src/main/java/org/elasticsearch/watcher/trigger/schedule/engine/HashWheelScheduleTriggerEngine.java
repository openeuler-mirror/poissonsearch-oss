/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.trigger.schedule.engine;

import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.netty.util.HashedWheelTimer;
import org.elasticsearch.common.netty.util.Timeout;
import org.elasticsearch.common.netty.util.TimerTask;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.EsThreadPoolExecutor;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.support.ThreadPoolSettingsBuilder;
import org.elasticsearch.watcher.support.clock.Clock;
import org.elasticsearch.watcher.trigger.schedule.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 *
 */
public class HashWheelScheduleTriggerEngine extends ScheduleTriggerEngine {

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
    private final EsThreadPoolExecutor executor;

    private final TimeValue tickDuration;
    private final int ticksPerWheel;

    private volatile HashedWheelTimer timer;
    private volatile Schedules schedules;

    @Inject
    public HashWheelScheduleTriggerEngine(Settings settings, Clock clock, ScheduleRegistry scheduleRegistry, ThreadPool threadPool) {
        super(settings, scheduleRegistry);
        this.clock = clock;
        this.executor = (EsThreadPoolExecutor) threadPool.executor(THREAD_POOL_NAME);
        this.tickDuration = componentSettings.getAsTime("hashweel.tick_duration", TimeValue.timeValueMillis(500));
        this.ticksPerWheel = componentSettings.getAsInt("hashweel.tick_per_wheel", 8196);
    }

    @Override
    public void start(Collection<Job> jobs) {
        logger.debug("starting schedule engine...");

        this.timer = new HashedWheelTimer(EsExecutors.daemonThreadFactory("trigger_engine_scheduler"), tickDuration.millis(), TimeUnit.MILLISECONDS, ticksPerWheel);

        // calibrate with round clock
        while (clock.millis() % 1000 > 15) {
        }
        timer.start();

        long starTime = clock.millis();
        List<ActiveSchedule> schedules = new ArrayList<>();
        for (Job job : jobs) {
            if (job.trigger() instanceof ScheduleTrigger) {
                ScheduleTrigger trigger = (ScheduleTrigger) job.trigger();
                schedules.add(new ActiveSchedule(job.name(), trigger.schedule(), starTime));
            }
        }
        this.schedules = new Schedules(schedules);
        logger.debug("schedule engine started at [{}]", DateTime.now());
    }

    @Override
    public void stop() {
        logger.debug("stopping schedule engine...");
        timer.stop();
        executor.getQueue().clear();
        logger.debug("schedule engine stopped");
    }

    @Override
    public void add(Job job) {
        assert job.trigger() instanceof ScheduleTrigger;
        ScheduleTrigger trigger = (ScheduleTrigger) job.trigger();
        ActiveSchedule schedule = new ActiveSchedule(job.name(), trigger.schedule(), clock.millis());
        schedules = schedules.add(schedule);
    }

    @Override
    public boolean remove(String jobName) {
        Schedules newSchedules = schedules.remove(jobName);
        if (newSchedules == null) {
            return false;
        }
        schedules = newSchedules;
        return true;
    }

    void notifyListeners(String name, long triggeredTime, long scheduledTime) {
        logger.trace("triggered job [{}] at [{}] (scheduled time was [{}])", name, new DateTime(triggeredTime), new DateTime(scheduledTime));
        final ScheduleTriggerEvent event = new ScheduleTriggerEvent(new DateTime(triggeredTime), new DateTime(scheduledTime));
        for (Listener listener : listeners) {
            executor.execute(new ListenerRunnable(listener, name, event));
        }
    }

    class ActiveSchedule implements TimerTask {

        private final String name;
        private final Schedule schedule;
        private final long startTime;

        private volatile Timeout timeout;
        private volatile long scheduledTime;

        public ActiveSchedule(String name, Schedule schedule, long startTime) {
            this.name = name;
            this.schedule = schedule;
            this.startTime = startTime;
            // we don't want the schedule to trigger on the start time itself, so we compute
            // the next scheduled time by simply computing the schedule time on the startTime + 1
            this.scheduledTime = schedule.nextScheduledTimeAfter(startTime, startTime + 1);
            long delay = scheduledTime > 0 ? scheduledTime - clock.millis() : -1;
            if (delay > 0) {
                timeout = timer.newTimeout(this, delay, TimeUnit.MILLISECONDS);
            } else {
                timeout = null;
            }
        }

        @Override
        public void run(Timeout timeout) {
            long triggeredTime = clock.millis();
            notifyListeners(name, triggeredTime, scheduledTime);
            scheduledTime = schedule.nextScheduledTimeAfter(startTime, triggeredTime);
            long delay = scheduledTime > 0 ? scheduledTime - triggeredTime : -1;
            if (delay > 0) {
                this.timeout = timer.newTimeout(this, delay, TimeUnit.MILLISECONDS);
            }
        }

        public void cancel() {
            if (timeout != null) {
                timeout.cancel();
            }
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

    static class Schedules {

        private final ActiveSchedule[] schedules;
        private final ImmutableMap<String, ActiveSchedule> scheduleByName;

        Schedules(Collection<ActiveSchedule> schedules) {
            ImmutableMap.Builder<String, ActiveSchedule> builder = ImmutableMap.builder();
            this.schedules = new ActiveSchedule[schedules.size()];
            int i = 0;
            for (ActiveSchedule schedule : schedules) {
                builder.put(schedule.name, schedule);
                this.schedules[i++] = schedule;
            }
            this.scheduleByName = builder.build();
        }

        public Schedules(ActiveSchedule[] schedules, ImmutableMap<String, ActiveSchedule> scheduleByName) {
            this.schedules = schedules;
            this.scheduleByName = scheduleByName;
        }

        public long getNextScheduledTime() {
            long time = -1;
            for (ActiveSchedule schedule : schedules) {
                if (time < 0) {
                    time = schedule.scheduledTime;
                } else {
                    time = Math.min(time, schedule.scheduledTime);
                }
            }
            return time;
        }

        public Schedules add(ActiveSchedule schedule) {
            boolean replacing = scheduleByName.containsKey(schedule.name);
            if (!replacing) {
                ActiveSchedule[] newSchedules = new ActiveSchedule[schedules.length + 1];
                System.arraycopy(schedules, 0, newSchedules, 0, schedules.length);
                newSchedules[schedules.length] = schedule;
                ImmutableMap<String, ActiveSchedule> newScheduleByName = ImmutableMap.<String, ActiveSchedule>builder()
                        .putAll(scheduleByName)
                        .put(schedule.name, schedule)
                        .build();
                return new Schedules(newSchedules, newScheduleByName);
            }
            ActiveSchedule[] newSchedules = new ActiveSchedule[schedules.length];
            ImmutableMap.Builder<String, ActiveSchedule> builder = ImmutableMap.builder();
            for (int i = 0; i < schedules.length; i++) {
                ActiveSchedule sched = schedules[i].name.equals(schedule.name) ? schedule : schedules[i];
                schedules[i] = sched;
                builder.put(sched.name, sched);
            }
            return new Schedules(newSchedules, builder.build());
        }

        public Schedules remove(String name) {
            if (!scheduleByName.containsKey(name)) {
                return null;
            }
            ImmutableMap.Builder<String, ActiveSchedule> builder = ImmutableMap.builder();
            ActiveSchedule[] newSchedules = new ActiveSchedule[schedules.length - 1];
            int i = 0;
            for (ActiveSchedule schedule : schedules) {
                if (!schedule.name.equals(name)) {
                    newSchedules[i++] = schedule;
                    builder.put(schedule.name, schedule);
                } else {
                    schedule.cancel();
                }
            }
            return new Schedules(newSchedules, builder.build());
        }
    }

}
