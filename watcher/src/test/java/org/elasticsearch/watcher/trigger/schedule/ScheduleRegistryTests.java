/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.trigger.schedule;


import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.junit.Before;

import java.util.HashMap;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 *
 */
public class ScheduleRegistryTests extends ScheduleTestCase {
    private ScheduleRegistry registry;

    @Before
    public void init() throws Exception {
        Map<String, Schedule.Parser> parsers = new HashMap<>();
        parsers.put(IntervalSchedule.TYPE, new IntervalSchedule.Parser());
        parsers.put(CronSchedule.TYPE, new CronSchedule.Parser());
        parsers.put(HourlySchedule.TYPE, new HourlySchedule.Parser());
        parsers.put(DailySchedule.TYPE, new DailySchedule.Parser());
        parsers.put(WeeklySchedule.TYPE, new WeeklySchedule.Parser());
        parsers.put(MonthlySchedule.TYPE, new MonthlySchedule.Parser());
        registry = new ScheduleRegistry(parsers);
    }

    public void testParserInterval() throws Exception {
        IntervalSchedule interval = randomIntervalSchedule();
        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(IntervalSchedule.TYPE, interval)
                .endObject();
        BytesReference bytes = builder.bytes();
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();
        Schedule schedule = registry.parse("ctx", parser);
        assertThat(schedule, notNullValue());
        assertThat(schedule, instanceOf(IntervalSchedule.class));
        assertThat((IntervalSchedule) schedule, is(interval));
    }

    public void testParseCron() throws Exception {
        Object cron = randomBoolean() ?
                Schedules.cron("* 0/5 * * * ?") :
                Schedules.cron("* 0/2 * * * ?", "* 0/3 * * * ?", "* 0/5 * * * ?");
        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(CronSchedule.TYPE, cron)
                .endObject();
        BytesReference bytes = builder.bytes();
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();
        Schedule schedule = registry.parse("ctx", parser);
        assertThat(schedule, notNullValue());
        assertThat(schedule, instanceOf(CronSchedule.class));
        assertThat(schedule, is(cron));
    }

    public void testParseHourly() throws Exception {
        HourlySchedule hourly = randomHourlySchedule();
        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(HourlySchedule.TYPE, hourly)
                .endObject();
        BytesReference bytes = builder.bytes();
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();
        Schedule schedule = registry.parse("ctx", parser);
        assertThat(schedule, notNullValue());
        assertThat(schedule, instanceOf(HourlySchedule.class));
        assertThat((HourlySchedule) schedule, equalTo(hourly));
    }

    public void testParseDaily() throws Exception {
        DailySchedule daily = randomDailySchedule();
        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(DailySchedule.TYPE, daily)
                .endObject();
        BytesReference bytes = builder.bytes();
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();
        Schedule schedule = registry.parse("ctx", parser);
        assertThat(schedule, notNullValue());
        assertThat(schedule, instanceOf(DailySchedule.class));
        assertThat((DailySchedule) schedule, equalTo(daily));
    }

    public void testParseWeekly() throws Exception {
        WeeklySchedule weekly = randomWeeklySchedule();
        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(WeeklySchedule.TYPE, weekly)
                .endObject();
        BytesReference bytes = builder.bytes();
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();
        Schedule schedule = registry.parse("ctx", parser);
        assertThat(schedule, notNullValue());
        assertThat(schedule, instanceOf(WeeklySchedule.class));
        assertThat((WeeklySchedule) schedule, equalTo(weekly));
    }

    public void testParseMonthly() throws Exception {
        MonthlySchedule monthly = randomMonthlySchedule();
        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(MonthlySchedule.TYPE, monthly)
                .endObject();
        BytesReference bytes = builder.bytes();
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();
        Schedule schedule = registry.parse("ctx", parser);
        assertThat(schedule, notNullValue());
        assertThat(schedule, instanceOf(MonthlySchedule.class));
        assertThat((MonthlySchedule) schedule, equalTo(monthly));
    }
}
