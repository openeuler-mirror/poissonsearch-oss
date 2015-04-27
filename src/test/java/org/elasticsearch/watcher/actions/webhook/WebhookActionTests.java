/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.webhook;

import com.carrotsearch.randomizedtesting.annotations.Repeat;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.joda.time.DateTime;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.WatcherException;
import org.elasticsearch.watcher.actions.Action;
import org.elasticsearch.watcher.actions.Action.Result.Status;
import org.elasticsearch.watcher.actions.ActionWrapper;
import org.elasticsearch.watcher.actions.email.service.*;
import org.elasticsearch.watcher.execution.TriggeredExecutionContext;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.execution.Wid;
import org.elasticsearch.watcher.support.http.*;
import org.elasticsearch.watcher.support.http.auth.HttpAuthFactory;
import org.elasticsearch.watcher.support.http.auth.HttpAuthRegistry;
import org.elasticsearch.watcher.support.http.auth.basic.BasicAuthFactory;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.support.secret.SecretService;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.support.template.TemplateEngine;
import org.elasticsearch.watcher.support.template.xmustache.XMustacheTemplateEngine;
import org.elasticsearch.watcher.test.WatcherTestUtils;
import org.elasticsearch.watcher.trigger.schedule.ScheduleTriggerEvent;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.watch.Watch;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.mail.internet.AddressException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.common.joda.time.DateTimeZone.UTC;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;


/**
 */
public class WebhookActionTests extends ElasticsearchTestCase {

    static final String TEST_HOST = "test.com";
    static final int TEST_PORT = 8089;

    private ThreadPool tp = null;
    private ScriptServiceProxy scriptService;
    private TemplateEngine templateEngine;
    private HttpAuthRegistry authRegistry;
    private Template testBody;
    private Template testPath;

    static final String TEST_BODY_STRING = "ERROR HAPPENED";
    static final String TEST_PATH_STRING = "/testPath";


    @Before
    public void init() throws Exception {
        tp = new ThreadPool(ThreadPool.Names.SAME);
        Settings settings = ImmutableSettings.EMPTY;
        scriptService = WatcherTestUtils.getScriptServiceProxy(tp);
        templateEngine = new XMustacheTemplateEngine(settings, scriptService);
        SecretService secretService = mock(SecretService.class);
        testBody = Template.inline(TEST_BODY_STRING).build();
        testPath = Template.inline(TEST_PATH_STRING).build();
        authRegistry = new HttpAuthRegistry(ImmutableMap.of("basic", (HttpAuthFactory) new BasicAuthFactory(secretService)));
    }

    @After
    public void cleanup() {
        tp.shutdownNow();
    }

    @Test @Repeat(iterations = 30)
    public void testExecute() throws Exception {
        ClientProxy client = mock(ClientProxy.class);
        ExecuteScenario scenario = randomFrom(ExecuteScenario.Success, ExecuteScenario.ErrorCode);

        HttpClient httpClient = scenario.client();
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD);

        final String account = "account1";

        HttpRequestTemplate httpRequest = getHttpRequestTemplate(method, TEST_HOST, TEST_PORT, testPath, testBody, null);

        WebhookAction action = new WebhookAction(httpRequest);
        ExecutableWebhookAction executable = new ExecutableWebhookAction(action, logger, httpClient, templateEngine);

        Watch watch = createWatch("test_watch", client, account);
        WatchExecutionContext ctx = new TriggeredExecutionContext(watch, new DateTime(), new ScheduleTriggerEvent(watch.id(), new DateTime(), new DateTime()), timeValueSeconds(5));

        Action.Result actionResult = executable.execute("_id", ctx, Payload.EMPTY);
        scenario.assertResult(httpClient, actionResult);
    }

    private HttpRequestTemplate getHttpRequestTemplate(HttpMethod method, String host, int port, Template path, Template body, Map<String, Template> params) {
        HttpRequestTemplate.Builder builder = HttpRequestTemplate.builder(host, port);
        if (path != null) {
            builder.path(path);
        }
        if (body != null) {
            builder.body(body);
        }
        if (method != null) {
            builder.method(method);
        }
        if (params != null){
            builder.putParams(params);
        }
        return builder.build();
    }

    @Test @Repeat(iterations = 10)
    public void testParser() throws Exception {
        Template body = randomBoolean() ? Template.inline("_subject").build() : null;
        Template path = Template.inline("_url").build();
        String host = "test.host";
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD, null);
        HttpRequestTemplate request = getHttpRequestTemplate(method, host, TEST_PORT, path, body, null);

        XContentBuilder builder = jsonBuilder();
        request.toXContent(builder, Attachment.XContent.EMPTY_PARAMS);

        WebhookActionFactory actionParser = webhookFactory(ExecuteScenario.Success.client());

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        ExecutableWebhookAction executable = actionParser.parseExecutable(randomAsciiOfLength(5), randomAsciiOfLength(5), parser);

        assertThat(executable.action().getRequest(), equalTo(request));
    }

    @Test @Repeat(iterations = 10)
    public void testParser_SelfGenerated() throws Exception {
        Template body = randomBoolean() ? Template.inline("_body").build() : null;
        Template path = Template.inline("_url").build();
        String host = "test.host";
        String watchId = "_watch";
        String actionId = randomAsciiOfLength(5);

        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD, null);

        HttpRequestTemplate request = getHttpRequestTemplate(method, host, TEST_PORT, path, body, null);
        WebhookAction action = new WebhookAction(request);
        ExecutableWebhookAction executable = new ExecutableWebhookAction(action, logger, ExecuteScenario.Success.client(), templateEngine);

        XContentBuilder builder = jsonBuilder();
        executable.toXContent(builder, ToXContent.EMPTY_PARAMS);

        WebhookActionFactory actionParser = webhookFactory(ExecuteScenario.Success.client());

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        ExecutableWebhookAction parsedExecutable = actionParser.parseExecutable(watchId, actionId, parser);
        assertThat(parsedExecutable, notNullValue());
        assertThat(parsedExecutable.action(), is(action));
    }

    @Test @Repeat(iterations = 10)
    public void testParser_Builder() throws Exception {
        Template body = randomBoolean() ? Template.inline("_body").build() : null;
        Template path = Template.inline("_url").build();
        String host = "test.host";

        String watchId = "_watch";
        String actionId = randomAsciiOfLength(5);

        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD,  null);
        HttpRequestTemplate request = getHttpRequestTemplate(method, host, TEST_PORT, path, body, null);

        WebhookAction action = WebhookAction.builder(request).build();

        XContentBuilder builder = jsonBuilder();
        action.toXContent(builder, ToXContent.EMPTY_PARAMS);

        WebhookActionFactory actionParser = webhookFactory(ExecuteScenario.Success.client());

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        assertThat(parser.nextToken(), is(XContentParser.Token.START_OBJECT));
        ExecutableWebhookAction parsedAction = actionParser.parseExecutable(watchId, actionId, parser);
        assertThat(parsedAction.action(), is(action));
    }

    @Test(expected = WebhookActionException.class)
    @Repeat(iterations = 5)
    public void testParser_Failure() throws Exception {
        XContentBuilder builder = jsonBuilder().startObject();
        if (randomBoolean()) {
            builder.field(HttpRequestTemplate.Parser.HOST_FIELD.getPreferredName(), TEST_HOST);
        } else {
            builder.field(HttpRequestTemplate.Parser.PORT_FIELD.getPreferredName(), TEST_PORT);
        }
        builder.endObject();

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        WebhookActionFactory actionParser = webhookFactory(ExecuteScenario.Success.client());
        //This should fail since we are not supplying a url
        actionParser.parseExecutable("_watch", randomAsciiOfLength(5), parser);
        fail("expected a WebhookActionException since we only provided either a host or a port but not both");
    }

    @Test @Repeat(iterations = 30)
    public void testParser_Result() throws Exception {
        String body = "_body";
        String host = "test.host";
        String path = "/_url";
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD);

        Wid wid = new Wid("_watch", randomLong(), DateTime.now());
        String actionId = randomAsciiOfLength(5);

        HttpRequest request = HttpRequest.builder(host, 123)
                .path(path)
                .body(body)
                .method(method)
                .build();

        HttpResponse response = new HttpResponse(randomIntBetween(200, 599), randomAsciiOfLength(10).getBytes(UTF8));

        Status status = randomFrom(Status.values());
        boolean responseError = status == Status.FAILURE && randomBoolean();

        HttpClient client = status == Status.SUCCESS ? ExecuteScenario.Success.client() :
                responseError ? ExecuteScenario.ErrorCode.client() :
                        status == Status.FAILURE ? ExecuteScenario.Error.client() : ExecuteScenario.NoExecute.client();


        WebhookActionFactory actionParser = webhookFactory(client);

        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(WebhookAction.Field.STATUS.getPreferredName(), status.name().toLowerCase(Locale.ROOT));

        switch (status) {
            case SUCCESS:
                builder.field(WebhookAction.Field.REQUEST.getPreferredName(), request);
                builder.field(WebhookAction.Field.RESPONSE.getPreferredName(), response);
                break;
            case SIMULATED:
                builder.field(WebhookAction.Field.REQUEST.getPreferredName(), request);
                break;
            case FAILURE:
                if (responseError) {
                    builder.field(WebhookAction.Field.REASON.getPreferredName(), "status_code_failure_reason");
                    builder.field(WebhookAction.Field.REQUEST.getPreferredName(), request);
                    builder.field(WebhookAction.Field.RESPONSE.getPreferredName(), response);
                } else {
                    builder.field(WebhookAction.Field.REASON.getPreferredName(), "failure_reason");
                }
                break;
            case THROTTLED:
                builder.field(WebhookAction.Field.REASON.getPreferredName(), "throttle_reason");
                break;
            default:
                throw new WatcherException("unsupported action result status [{}]", status.name());
        }
        builder.endObject();

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        Action.Result result = actionParser.parseResult(wid, actionId, parser);

        assertThat(result.status(), is(status));

        switch (status) {
            case SUCCESS:
                assertThat(result, instanceOf(WebhookAction.Result.Success.class));
                WebhookAction.Result.Success success = (WebhookAction.Result.Success) result;
                assertThat(success.request(), equalTo(request));
                assertThat(success.response(), equalTo(response));
                break;
            case SIMULATED:
                assertThat(result, instanceOf(WebhookAction.Result.Simulated.class));
                WebhookAction.Result.Simulated simulated = (WebhookAction.Result.Simulated) result;
                assertThat(simulated.request(), equalTo(request));
                break;
            case FAILURE:
                if (responseError) {
                    assertThat(result, instanceOf(WebhookAction.Result.Failure.class));
                    WebhookAction.Result.Failure responseFailure = (WebhookAction.Result.Failure) result;
                    assertThat(responseFailure.reason(), is("status_code_failure_reason"));
                    assertThat(responseFailure.request(), equalTo(request));
                    assertThat(responseFailure.response(), equalTo(response));
                } else {
                    assertThat(result, instanceOf(Action.Result.Failure.class));
                    Action.Result.Failure failure = (Action.Result.Failure) result;
                    assertThat(failure.reason(), is("failure_reason"));
                }
                break;
            case THROTTLED:
                assertThat(result, instanceOf(Action.Result.Throttled.class));
                Action.Result.Throttled throttled = (Action.Result.Throttled) result;
                assertThat(throttled.reason(), is("throttle_reason"));
        }

    }

    @Test @Repeat(iterations = 5)
    public void testParser_Result_Simulated() throws Exception {
        String body = "_body";
        String host = "test.host";
        String path = "/_url";
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.HEAD);

        Wid wid = new Wid("_watch", randomLong(), DateTime.now());
        String actionId = randomAsciiOfLength(5);

        HttpRequest request = HttpRequest.builder(host, 123)
                .path(path)
                .body(body)
                .method(method)
                .build();

        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(Action.Field.STATUS.getPreferredName(), Status.SIMULATED.name().toLowerCase(Locale.ROOT))
                .field(WebhookAction.Field.REQUEST.getPreferredName(), request)
                .endObject();

        HttpClient client = ExecuteScenario.Success.client();

        WebhookActionFactory actionParser = webhookFactory(client);
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        Action.Result result = actionParser.parseResult(wid, actionId, parser);
        assertThat(result, instanceOf(WebhookAction.Result.Simulated.class));
        assertThat(((WebhookAction.Result.Simulated) result).request(), equalTo(request));
    }


    @Test
    public void testParser_Result_Simulated_SelfGenerated() throws Exception {
        String body = "_body";
        String host = "test.host";
        String path = "/_url";
        HttpMethod method = HttpMethod.GET;

        HttpRequest request = HttpRequest.builder(host, 123)
                .path(path)
                .body(body)
                .method(method)
                .build();

        Wid wid = new Wid("_watch", randomLong(), DateTime.now());
        String actionId = randomAsciiOfLength(5);

        WebhookAction.Result.Simulated simulatedResult = new WebhookAction.Result.Simulated(request);

        XContentBuilder builder = XContentFactory.jsonBuilder();
        simulatedResult.toXContent(builder, ToXContent.EMPTY_PARAMS);

        BytesReference bytes = builder.bytes();
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();

        Action.Result result = webhookFactory(ExecuteScenario.Success.client())
                .parseResult(wid, actionId, parser);

        assertThat(result, instanceOf(WebhookAction.Result.Simulated.class));
        assertThat(((WebhookAction.Result.Simulated)result).request(), equalTo(request));
    }


    private WebhookActionFactory webhookFactory(HttpClient client) {
        return new WebhookActionFactory(ImmutableSettings.EMPTY, client, new HttpRequest.Parser(authRegistry),
                new HttpRequestTemplate.Parser(authRegistry), templateEngine);
    }

    @Test
    @Repeat(iterations = 10)
    public void testTemplatedHttpRequest() throws Exception
    {
        HttpClient httpClient = ExecuteScenario.Success.client();

        String body = "{{ctx.watch_id}}";
        String host = "testhost";
        String path = randomFrom("{{ctx.execution_time}}", "{{ctx.trigger.scheduled_time}}", "{{ctx.trigger.triggered_time}}");

        Map<String, Template> params = new HashMap<>();
        params.put("foo", Template.inline(randomFrom("{{ctx.execution_time}}", "{{ctx.trigger.scheduled_time}}", "{{ctx.trigger.triggered_time}}")).build());
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT);

        HttpRequestTemplate request = getHttpRequestTemplate(method, host, TEST_PORT, Template.inline(path).build(), Template.inline(body).build(), params);

        String watchId = "_watch";
        String actionId = randomAsciiOfLength(5);

        WebhookAction action = WebhookAction.builder(request).build();

        ExecutableWebhookAction webhookAction = new ExecutableWebhookAction(action, logger, httpClient, templateEngine);

        DateTime time = new DateTime(UTC);
        Watch watch = createWatch(watchId, mock(ClientProxy.class), "account1");
        WatchExecutionContext ctx = new TriggeredExecutionContext(watch, time, new ScheduleTriggerEvent(watchId, time, time), timeValueSeconds(5));
        Action.Result result = webhookAction.execute(actionId, ctx, Payload.EMPTY);

        assertThat(result, Matchers.instanceOf(WebhookAction.Result.Success.class));
        WebhookAction.Result.Success success = (WebhookAction.Result.Success) result;
        assertThat(success.request().body(), equalTo(watchId));
        assertThat(success.request().path(), equalTo(time.toString()));
        assertThat(success.request().params().get("foo"), equalTo(time.toString()));

    }

    @Test @Repeat(iterations = 100)
    public void testValidUrls() throws Exception {

        HttpClient httpClient = ExecuteScenario.Success.client();
        HttpMethod method = HttpMethod.POST;
        Template path = Template.defaultType("/test_{{ctx.watch_id}}").build();
        String host = "test.host";
        HttpRequestTemplate requestTemplate = getHttpRequestTemplate(method, host, TEST_PORT, path, testBody, null);
        WebhookAction action = new WebhookAction(requestTemplate);

        ExecutableWebhookAction executable = new ExecutableWebhookAction(action, logger, httpClient, templateEngine);

        String watchId = "test_url_encode" + randomAsciiOfLength(10);
        Watch watch = createWatch(watchId, mock(ClientProxy.class), "account1");
        WatchExecutionContext ctx = new TriggeredExecutionContext(watch, new DateTime(UTC), new ScheduleTriggerEvent(watchId, new DateTime(UTC), new DateTime(UTC)), timeValueSeconds(5));
        Action.Result result = executable.execute("_id", ctx, new Payload.Simple());
        assertThat(result, Matchers.instanceOf(WebhookAction.Result.Success.class));
    }

    private Watch createWatch(String watchId, ClientProxy client, final String account) throws AddressException, IOException {
        return WatcherTestUtils.createTestWatch(watchId,
                client,
                scriptService,
                ExecuteScenario.Success.client(),
                new EmailService() {
                    @Override
                    public EmailSent send(Email email, Authentication auth, Profile profile) {
                        return new EmailSent(account, email);
                    }

                    @Override
                    public EmailSent send(Email email, Authentication auth, Profile profile, String accountName) {
                        return new EmailSent(account, email);
                    }
                },
                logger);
    }


    private enum ExecuteScenario {
        ErrorCode() {
            @Override
            public HttpClient client() throws IOException {
                HttpClient client = mock(HttpClient.class);
                when(client.execute(any(HttpRequest.class))).thenReturn(new HttpResponse(randomIntBetween(400, 599)));
                return client;
            }

            @Override
            public void assertResult(HttpClient client, Action.Result actionResult) throws Exception {
                assertThat(actionResult.status(), is(Status.FAILURE));
                assertThat(actionResult, instanceOf(WebhookAction.Result.Failure.class));
                WebhookAction.Result.Failure executedActionResult = (WebhookAction.Result.Failure) actionResult;
                assertThat(executedActionResult.response().status(), greaterThanOrEqualTo(400));
                assertThat(executedActionResult.response().status(), lessThanOrEqualTo(599));
                assertThat(executedActionResult.request().body(), equalTo(TEST_BODY_STRING));
                assertThat(executedActionResult.request().path(), equalTo(TEST_PATH_STRING));
            }
        },

        Error() {
            @Override
            public HttpClient client() throws IOException {
                HttpClient client = mock(HttpClient.class);
                when(client.execute(any(HttpRequest.class)))
                        .thenThrow(new IOException("Unable to connect"));
                return client;
            }

            @Override
            public void assertResult(HttpClient client, Action.Result actionResult) throws Exception {
                assertThat(actionResult, instanceOf(WebhookAction.Result.Failure.class));
                WebhookAction.Result.Failure failResult = (WebhookAction.Result.Failure) actionResult;
                assertThat(failResult.status(), is(Status.FAILURE));
            }
        },

        Success() {
            @Override
            public HttpClient client() throws IOException{
                HttpClient client = mock(HttpClient.class);
                when(client.execute(any(HttpRequest.class)))
                        .thenReturn(new HttpResponse(randomIntBetween(200, 399)));
                return client;
            }

            @Override
            public void assertResult(HttpClient client, Action.Result actionResult) throws Exception {
                assertThat(actionResult.status(), is(Status.SUCCESS));
                assertThat(actionResult, instanceOf(WebhookAction.Result.Success.class));
                WebhookAction.Result.Success executedActionResult = (WebhookAction.Result.Success) actionResult;
                assertThat(executedActionResult.response().status(), greaterThanOrEqualTo(200));
                assertThat(executedActionResult.response().status(), lessThanOrEqualTo(399));
                assertThat(executedActionResult.request().body(), equalTo(TEST_BODY_STRING));
                assertThat(executedActionResult.request().path(), equalTo(TEST_PATH_STRING));
            }
        },

        NoExecute() {
            @Override
            public HttpClient client() throws IOException{
                return mock(HttpClient.class);
            }

            @Override
            public void assertResult(HttpClient client, Action.Result actionResult) throws Exception {
                verify(client, never()).execute(any(HttpRequest.class));
            }
        };

        public abstract HttpClient client() throws IOException;

        public abstract void assertResult(HttpClient client, Action.Result result) throws Exception ;
    }

}
