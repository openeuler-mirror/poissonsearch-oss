/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.test;

import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.watcher.actions.ActionWrapper;
import org.elasticsearch.watcher.actions.ExecutableActions;
import org.elasticsearch.watcher.actions.email.EmailAction;
import org.elasticsearch.watcher.actions.email.ExecutableEmailAction;
import org.elasticsearch.watcher.actions.email.service.Authentication;
import org.elasticsearch.watcher.actions.email.service.EmailService;
import org.elasticsearch.watcher.actions.email.service.EmailTemplate;
import org.elasticsearch.watcher.actions.email.service.Profile;
import org.elasticsearch.watcher.actions.webhook.ExecutableWebhookAction;
import org.elasticsearch.watcher.actions.webhook.WebhookAction;
import org.elasticsearch.watcher.condition.script.ExecutableScriptCondition;
import org.elasticsearch.watcher.condition.script.ScriptCondition;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.input.search.SearchInput;
import org.elasticsearch.watcher.input.simple.SimpleInput;
import org.elasticsearch.watcher.license.LicenseService;
import org.elasticsearch.watcher.support.Script;
import org.elasticsearch.watcher.support.WatcherUtils;
import org.elasticsearch.watcher.support.clock.SystemClock;
import org.elasticsearch.watcher.support.http.HttpClient;
import org.elasticsearch.watcher.support.http.HttpMethod;
import org.elasticsearch.watcher.support.http.HttpRequestTemplate;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.support.template.MustacheTemplateEngine;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.support.template.TemplateEngine;
import org.elasticsearch.watcher.transform.SearchTransform;
import org.elasticsearch.watcher.trigger.TriggerEvent;
import org.elasticsearch.watcher.trigger.schedule.CronSchedule;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTrigger;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.Watch;

import javax.mail.internet.AddressException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.search.builder.SearchSourceBuilder.searchSource;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 *
 */
public final class WatcherTestUtils {

    public static final Payload EMPTY_PAYLOAD = new Payload.Simple(ImmutableMap.<String, Object>of());

    private WatcherTestUtils() {
    }

    public static SearchRequest newInputSearchRequest(String... indices) {
        SearchRequest request = new SearchRequest(indices);
        request.indicesOptions(WatcherUtils.DEFAULT_INDICES_OPTIONS);
        request.searchType(SearchInput.DEFAULT_SEARCH_TYPE);
        return request;
    }

    public static SearchRequest matchAllRequest() {
        return matchAllRequest(null);
    }

    public static SearchRequest matchAllRequest(IndicesOptions indicesOptions) {
        SearchRequest request = new SearchRequest(Strings.EMPTY_ARRAY)
                .source(SearchSourceBuilder.searchSource().query(matchAllQuery()).buildAsBytes(XContentType.JSON), false);
        if (indicesOptions != null) {
            request.indicesOptions(indicesOptions);
        }
        return request;
    }

    public static Payload simplePayload(String key, Object value) {
        return new Payload.Simple(key, value);
    }

    public static WatchExecutionContextMockBuilder mockExecutionContextBuilder(String watchId) {
        return new WatchExecutionContextMockBuilder(watchId);
    }

    public static WatchExecutionContext mockExecutionContext(String watchId, Payload payload) {
        return mockExecutionContextBuilder(watchId)
                .payload(payload)
                .buildMock();
    }

    public static WatchExecutionContext mockExecutionContext(String watchId, DateTime time, Payload payload) {
        return mockExecutionContextBuilder(watchId)
                .payload(payload)
                .time(time)
                .buildMock();
    }

    public static WatchExecutionContext mockExecutionContext(String watchId, DateTime executionTime, TriggerEvent event, Payload payload) {
        return mockExecutionContextBuilder(watchId)
                .payload(payload)
                .executionTime(executionTime)
                .triggerEvent(event)
                .buildMock();
    }


    public static Watch createTestWatch(String watchName, ScriptServiceProxy scriptService, HttpClient httpClient, EmailService emailService, ESLogger logger) throws AddressException {
        return createTestWatch(watchName, ClientProxy.of(ElasticsearchIntegrationTest.client()), scriptService, httpClient, emailService, logger);
    }


    public static Watch createTestWatch(String watchName, ClientProxy client, ScriptServiceProxy scriptService, HttpClient httpClient, EmailService emailService, ESLogger logger) throws AddressException {

        SearchRequest conditionRequest = newInputSearchRequest("my-condition-index").source(searchSource().query(matchAllQuery()));
        SearchRequest transformRequest = newInputSearchRequest("my-payload-index").source(searchSource().query(matchAllQuery()));
        transformRequest.searchType(SearchTransform.DEFAULT_SEARCH_TYPE);
        conditionRequest.searchType(SearchInput.DEFAULT_SEARCH_TYPE);

        List<ActionWrapper> actions = new ArrayList<>();

        HttpRequestTemplate.Builder httpRequest = HttpRequestTemplate.builder("localhost", 80);
        httpRequest.method(HttpMethod.POST);

        Template path = new Template("/foobarbaz/{{ctx.watch_id}}");
        httpRequest.path(path);
        Template body = new Template("{{ctx.watch_id}} executed with {{ctx.payload.response.hits.total_hits}} hits");
        httpRequest.body(body);

        TemplateEngine engine = new MustacheTemplateEngine(ImmutableSettings.EMPTY, scriptService);

        actions.add(new ActionWrapper("_webhook", new ExecutableWebhookAction(new WebhookAction(httpRequest.build()), logger, httpClient, engine)));

        String from = "from@test.com";
        String to = "to@test.com";

        EmailTemplate email = EmailTemplate.builder()
                .from(from)
                .to(to)
                .build();

        TemplateEngine templateEngine = new MustacheTemplateEngine(ImmutableSettings.EMPTY, scriptService);

        Authentication auth = new Authentication("testname", "testpassword".toCharArray());

        EmailAction action = new EmailAction(email, "testaccount", auth, Profile.STANDARD, false);
        ExecutableEmailAction executale = new ExecutableEmailAction(action, logger, emailService, templateEngine);

        actions.add(new ActionWrapper("_email", executale));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("foo", "bar");

        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("bar", "foo");

        LicenseService licenseService = mock(LicenseService.class);
        when(licenseService.enabled()).thenReturn(true);

        return new Watch(
                watchName,
                SystemClock.INSTANCE,
                licenseService,
                new ScheduleTrigger(new CronSchedule("0/5 * * * * ? *")),
                new SimpleInput(logger, new Payload.Simple(inputData)),
                new ExecutableScriptCondition(new ScriptCondition(new Script("return true")), logger, scriptService),
                new SearchTransform(logger, scriptService, client, transformRequest),
                new ExecutableActions(actions),
                metadata,
                new TimeValue(0),
                new Watch.Status());
    }

}
