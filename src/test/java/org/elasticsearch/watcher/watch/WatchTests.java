/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.watch;

import com.carrotsearch.ant.tasks.junit4.dependencies.com.google.common.collect.ImmutableSet;
import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.watcher.actions.Action;
import org.elasticsearch.watcher.actions.ActionRegistry;
import org.elasticsearch.watcher.actions.Actions;
import org.elasticsearch.watcher.actions.email.EmailAction;
import org.elasticsearch.watcher.actions.email.service.Email;
import org.elasticsearch.watcher.actions.email.service.EmailService;
import org.elasticsearch.watcher.actions.email.service.Profile;
import org.elasticsearch.watcher.actions.index.IndexAction;
import org.elasticsearch.watcher.actions.webhook.WebhookAction;
import org.elasticsearch.watcher.condition.Condition;
import org.elasticsearch.watcher.condition.ConditionRegistry;
import org.elasticsearch.watcher.condition.script.ScriptCondition;
import org.elasticsearch.watcher.condition.simple.AlwaysTrueCondition;
import org.elasticsearch.watcher.input.Input;
import org.elasticsearch.watcher.input.InputRegistry;
import org.elasticsearch.watcher.input.search.SearchInput;
import org.elasticsearch.watcher.input.simple.SimpleInput;
import org.elasticsearch.watcher.support.Script;
import org.elasticsearch.watcher.support.WatcherUtils;
import org.elasticsearch.watcher.support.clock.SystemClock;
import org.elasticsearch.watcher.support.http.HttpClient;
import org.elasticsearch.watcher.support.http.HttpMethod;
import org.elasticsearch.watcher.support.http.HttpRequest;
import org.elasticsearch.watcher.support.http.TemplatedHttpRequest;
import org.elasticsearch.watcher.support.http.auth.BasicAuth;
import org.elasticsearch.watcher.support.http.auth.HttpAuth;
import org.elasticsearch.watcher.support.http.auth.HttpAuthRegistry;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.support.template.ScriptTemplate;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.test.WatcherTestUtils;
import org.elasticsearch.watcher.transform.*;
import org.elasticsearch.watcher.trigger.Trigger;
import org.elasticsearch.watcher.trigger.TriggerEngine;
import org.elasticsearch.watcher.trigger.TriggerService;
import org.elasticsearch.watcher.trigger.schedule.*;
import org.elasticsearch.watcher.trigger.schedule.support.*;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import static org.elasticsearch.watcher.test.WatcherTestUtils.matchAllRequest;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.mock;

public class WatchTests extends ElasticsearchTestCase {

    private ScriptServiceProxy scriptService;
    private ClientProxy client;
    private HttpClient httpClient;
    private EmailService emailService;
    private Template.Parser templateParser;
    private HttpAuthRegistry authRegistry;
    private ESLogger logger;
    private Settings settings = ImmutableSettings.EMPTY;

    @Before
    public void init() throws Exception {
        scriptService = mock(ScriptServiceProxy.class);
        client = mock(ClientProxy.class);
        httpClient = mock(HttpClient.class);
        emailService = mock(EmailService.class);
        templateParser = new ScriptTemplate.Parser(settings, scriptService);
        authRegistry = new HttpAuthRegistry(ImmutableMap.of("basic", (HttpAuth.Parser) new BasicAuth.Parser()));
        logger = Loggers.getLogger(WatchTests.class);
    }

    @Test @Repeat(iterations = 20)
    public void testParser_SelfGenerated() throws Exception {

        TransformRegistry transformRegistry = transformRegistry();

        Schedule schedule = randomSchedule();
        Trigger trigger = new ScheduleTrigger(schedule);
        ScheduleRegistry scheduleRegistry = registry(schedule);
        TriggerEngine triggerEngine = new ParseOnlyScheduleTriggerEngine(ImmutableSettings.EMPTY, scheduleRegistry);
        TriggerService triggerService = new TriggerService(ImmutableSettings.EMPTY, ImmutableSet.of(triggerEngine));

        Input input = randomInput();
        InputRegistry inputRegistry = registry(input);

        Condition condition = randomCondition();
        ConditionRegistry conditionRegistry = registry(condition);

        Transform transform = randomTransform();

        Actions actions = randomActions();
        ActionRegistry actionRegistry = registry(actions, transformRegistry);

        Map<String, Object> metadata = ImmutableMap.<String, Object>of("_key", "_val");

        Watch.Status status = new Watch.Status();

        TimeValue throttlePeriod = randomBoolean() ? null : TimeValue.timeValueSeconds(randomIntBetween(5, 10));

        Watch watch = new Watch("_name", SystemClock.INSTANCE, trigger, input, condition, transform, actions, metadata, throttlePeriod, status);

        BytesReference bytes = XContentFactory.jsonBuilder().value(watch).bytes();
        logger.info(bytes.toUtf8());
        Watch.Parser watchParser = new Watch.Parser(settings, conditionRegistry, triggerService, transformRegistry, actionRegistry, inputRegistry, SystemClock.INSTANCE);

        boolean includeStatus = randomBoolean();
        Watch parsedWatch = watchParser.parse("_name", includeStatus, bytes);

        if (includeStatus) {
            assertThat(parsedWatch.status(), equalTo(status));
        }
        assertThat(parsedWatch.trigger(), equalTo(trigger));
        assertThat(parsedWatch.input(), equalTo(input));
        assertThat(parsedWatch.condition(), equalTo(condition));
        if (throttlePeriod != null) {
            assertThat(parsedWatch.throttlePeriod().millis(), equalTo(throttlePeriod.millis()));
        }
        assertThat(parsedWatch.metadata(), equalTo(metadata));
        assertThat(parsedWatch.actions(), equalTo(actions));
    }

    private static Schedule randomSchedule() {
        String type = randomFrom(CronSchedule.TYPE, HourlySchedule.TYPE, DailySchedule.TYPE, WeeklySchedule.TYPE, MonthlySchedule.TYPE, YearlySchedule.TYPE, IntervalSchedule.TYPE);
        switch (type) {
            case CronSchedule.TYPE:
                return new CronSchedule("0/5 * * * * ? *");
            case HourlySchedule.TYPE:
                return HourlySchedule.builder().minutes(30).build();
            case DailySchedule.TYPE:
                return DailySchedule.builder().atNoon().build();
            case WeeklySchedule.TYPE:
                return WeeklySchedule.builder().time(WeekTimes.builder().on(DayOfWeek.FRIDAY).atMidnight()).build();
            case MonthlySchedule.TYPE:
                return MonthlySchedule.builder().time(MonthTimes.builder().on(1).atNoon()).build();
            case YearlySchedule.TYPE:
                return YearlySchedule.builder().time(YearTimes.builder().in(Month.JANUARY).on(1).atMidnight()).build();
            default:
                return new IntervalSchedule(IntervalSchedule.Interval.seconds(5));
        }
    }

    private static ScheduleRegistry registry(Schedule schedule) {
        ImmutableMap.Builder<String, Schedule.Parser> parsers = ImmutableMap.builder();
        switch (schedule.type()) {
            case CronSchedule.TYPE:
                parsers.put(CronSchedule.TYPE, new CronSchedule.Parser());
                return new ScheduleRegistry(parsers.build());
            case HourlySchedule.TYPE:
                parsers.put(HourlySchedule.TYPE, new HourlySchedule.Parser());
                return new ScheduleRegistry(parsers.build());
            case DailySchedule.TYPE:
                parsers.put(DailySchedule.TYPE, new DailySchedule.Parser());
                return new ScheduleRegistry(parsers.build());
            case WeeklySchedule.TYPE:
                parsers.put(WeeklySchedule.TYPE, new WeeklySchedule.Parser());
                return new ScheduleRegistry(parsers.build());
            case MonthlySchedule.TYPE:
                parsers.put(MonthlySchedule.TYPE, new MonthlySchedule.Parser());
                return new ScheduleRegistry(parsers.build());
            case YearlySchedule.TYPE:
                parsers.put(YearlySchedule.TYPE, new YearlySchedule.Parser());
                return new ScheduleRegistry(parsers.build());
            case IntervalSchedule.TYPE:
                parsers.put(IntervalSchedule.TYPE, new IntervalSchedule.Parser());
                return new ScheduleRegistry(parsers.build());
            default:
                throw new IllegalArgumentException("unknown schedule [" + schedule + "]");
        }
    }

    private Input randomInput() {
        String type = randomFrom(SearchInput.TYPE, SimpleInput.TYPE);
        switch (type) {
            case SearchInput.TYPE:
                return new SearchInput(logger, scriptService, client, WatcherTestUtils.newInputSearchRequest("idx"), null);
            default:
                return new SimpleInput(logger, new Payload.Simple(ImmutableMap.<String, Object>builder().put("_key", "_val").build()));
        }
    }

    private InputRegistry registry(Input input) {
        ImmutableMap.Builder<String, Input.Parser> parsers = ImmutableMap.builder();
        switch (input.type()) {
            case SearchInput.TYPE:
                parsers.put(SearchInput.TYPE, new SearchInput.Parser(settings, scriptService, client));
                return new InputRegistry(parsers.build());
            default:
                parsers.put(SimpleInput.TYPE, new SimpleInput.Parser(settings));
                return new InputRegistry(parsers.build());
        }
    }

    private Condition randomCondition() {
        String type = randomFrom(ScriptCondition.TYPE, AlwaysTrueCondition.TYPE);
        switch (type) {
            case ScriptCondition.TYPE:
                return new ScriptCondition(logger, scriptService, new Script("_script"));
            default:
                return new AlwaysTrueCondition(logger);
        }
    }

    private ConditionRegistry registry(Condition condition) {
        ImmutableMap.Builder<String, Condition.Parser> parsers = ImmutableMap.builder();
        switch (condition.type()) {
            case ScriptCondition.TYPE:
                parsers.put(ScriptCondition.TYPE, new ScriptCondition.Parser(settings, scriptService));
                return new ConditionRegistry(parsers.build());
            default:
                parsers.put(AlwaysTrueCondition.TYPE, new AlwaysTrueCondition.Parser(settings));
                return new ConditionRegistry(parsers.build());
        }
    }

    private Transform randomTransform() {
        String type = randomFrom(ScriptTransform.TYPE, SearchTransform.TYPE, ChainTransform.TYPE);
        switch (type) {
            case ScriptTransform.TYPE:
                return new ScriptTransform(scriptService, new Script("_script"));
            case SearchTransform.TYPE:
                return new SearchTransform(logger, scriptService, client, matchAllRequest(WatcherUtils.DEFAULT_INDICES_OPTIONS));
            default: // chain
                return new ChainTransform(ImmutableList.<Transform>of(
                        new SearchTransform(logger, scriptService, client, matchAllRequest(WatcherUtils.DEFAULT_INDICES_OPTIONS)),
                        new ScriptTransform(scriptService, new Script("_script"))));
        }
    }

    private TransformRegistry transformRegistry() {
        ImmutableMap.Builder<String, Transform.Parser> parsers = ImmutableMap.builder();
        ChainTransform.Parser parser = new ChainTransform.Parser();
        parsers.put(ChainTransform.TYPE, parser);
        parsers.put(ScriptTransform.TYPE, new ScriptTransform.Parser(scriptService));
        parsers.put(SearchTransform.TYPE, new SearchTransform.Parser(settings, scriptService, client));
        TransformRegistry registry = new TransformRegistry(parsers.build());
        parser.init(registry);
        return registry;
    }

    private Actions randomActions() {
        ImmutableList.Builder<Action> list = ImmutableList.builder();
        if (randomBoolean()) {
            Transform transform = randomTransform();
            list.add(new EmailAction(logger, transform, emailService, Email.builder().id("prototype").build(), null, Profile.STANDARD, null, null, null, null, randomBoolean()));
        }
        if (randomBoolean()) {
            list.add(new IndexAction(logger, randomTransform(), client, "_index", "_type"));
        }
        if (randomBoolean()) {
            TemplatedHttpRequest httpRequest = new TemplatedHttpRequest();
            httpRequest.method(randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT));
            httpRequest.host("test.host");
            httpRequest.path(new ScriptTemplate(scriptService, "_url"));
            list.add(new WebhookAction(logger, randomTransform(), httpClient, httpRequest));
        }
        return new Actions(list.build());
    }

    private ActionRegistry registry(Actions actions, TransformRegistry transformRegistry) {
        ImmutableMap.Builder<String, Action.Parser> parsers = ImmutableMap.builder();
        for (Action action : actions) {
            switch (action.type()) {
                case EmailAction.TYPE:
                    parsers.put(EmailAction.TYPE, new EmailAction.Parser(settings, emailService, templateParser, transformRegistry));
                    break;
                case IndexAction.TYPE:
                    parsers.put(IndexAction.TYPE, new IndexAction.Parser(settings, client, transformRegistry));
                    break;
                case WebhookAction.TYPE:
                    parsers.put(WebhookAction.TYPE, new WebhookAction.Parser(settings,  httpClient, transformRegistry,
                            new HttpRequest.Parser(authRegistry),
                            new TemplatedHttpRequest.Parser(new ScriptTemplate.Parser(settings, scriptService), authRegistry)));
                    break;
            }
        }
        return new ActionRegistry(parsers.build());
    }


    static class ParseOnlyScheduleTriggerEngine extends ScheduleTriggerEngine {

        private final ScheduleRegistry registry;

        public ParseOnlyScheduleTriggerEngine(Settings settings, ScheduleRegistry registry) {
            super(settings);
            this.registry = registry;
        }

        @Override
        public void start(Collection<Job> jobs) {
        }

        @Override
        public void stop() {
        }

        @Override
        public void register(Listener listener) {
        }

        @Override
        public void add(Job job) {
        }

        @Override
        public boolean remove(String jobName) {
            return false;
        }

        @Override
        public ScheduleTrigger parseTrigger(String context, XContentParser parser) throws IOException {
            Schedule schedule = registry.parse(context, parser);
            return new ScheduleTrigger(schedule);
        }

        @Override
        public ScheduleTriggerEvent parseTriggerEvent(String context, XContentParser parser) throws IOException {
            return null;
        }
    }
}
