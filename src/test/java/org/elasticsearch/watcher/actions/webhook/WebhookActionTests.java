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
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.mustache.MustacheScriptEngineService;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.elasticsearch.watcher.actions.ActionException;
import org.elasticsearch.watcher.actions.email.service.*;
import org.elasticsearch.watcher.execution.TriggeredExecutionContext;
import org.elasticsearch.watcher.execution.WatchExecutionContext;
import org.elasticsearch.watcher.execution.Wid;
import org.elasticsearch.watcher.support.http.*;
import org.elasticsearch.watcher.support.http.auth.BasicAuth;
import org.elasticsearch.watcher.support.http.auth.HttpAuth;
import org.elasticsearch.watcher.support.http.auth.HttpAuthRegistry;
import org.elasticsearch.watcher.support.init.proxy.ClientProxy;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.support.template.MustacheTemplateEngine;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.support.template.TemplateEngine;
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
import java.util.HashSet;
import java.util.Set;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    public void init() throws IOException {
        tp = new ThreadPool(ThreadPool.Names.SAME);
        Settings settings = ImmutableSettings.EMPTY;
        MustacheScriptEngineService mustacheScriptEngineService = new MustacheScriptEngineService(settings);
        Set<ScriptEngineService> engineServiceSet = new HashSet<>();
        engineServiceSet.add(mustacheScriptEngineService);
        scriptService = ScriptServiceProxy.of(new ScriptService(settings, new Environment(), engineServiceSet, new ResourceWatcherService(settings, tp), new NodeSettingsService(settings)));
        templateEngine = new MustacheTemplateEngine(settings, scriptService);
        testBody = new Template(TEST_BODY_STRING );
        testPath = new Template(TEST_PATH_STRING);
        authRegistry = new HttpAuthRegistry(ImmutableMap.of("basic", (HttpAuth.Parser) new BasicAuth.Parser()));
    }

    @After
    public void cleanup() {
        tp.shutdownNow();
    }

    @Test @Repeat(iterations = 30)
    public void testExecute() throws Exception {
        ClientProxy client = mock(ClientProxy.class);
        ExecuteScenario scenario = randomFrom(ExecuteScenario.values());

        HttpClient httpClient = scenario.client();
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT);

        final String account = "account1";

        HttpRequestTemplate httpRequest = getHttpRequestTemplate(method, TEST_HOST, TEST_PORT, testPath, testBody);

        WebhookAction action = new WebhookAction(httpRequest);
        ExecutableWebhookAction executable = new ExecutableWebhookAction(action, logger, httpClient, templateEngine);

        Watch watch = createWatch("test_watch", client, account);
        WatchExecutionContext ctx = new TriggeredExecutionContext(watch, new DateTime(), new ScheduleTriggerEvent(new DateTime(), new DateTime()));

        WebhookAction.Result actionResult = executable.execute("_id", ctx, new Payload.Simple());
        scenario.assertResult(actionResult);
    }

    private HttpRequestTemplate getHttpRequestTemplate(HttpMethod method, String host, int port, Template path, Template body) {
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
        return builder.build();
    }

    @Test @Repeat(iterations = 10)
    public void testParser() throws Exception {
        Template body = randomBoolean() ? new Template("_subject") : null;
        Template path = new Template("_url");
        String host = "test.host";
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, null);
        HttpRequestTemplate request = getHttpRequestTemplate(method, host, TEST_PORT, path, body);

        XContentBuilder builder = jsonBuilder();
        request.toXContent(builder, Attachment.XContent.EMPTY_PARAMS);

        WebhookActionFactory actionParser = getParser(ExecuteScenario.Success.client());

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        ExecutableWebhookAction executable = actionParser.parseExecutable(randomAsciiOfLength(5), randomAsciiOfLength(5), parser);

        assertThat(executable.action().getRequest(), equalTo(request));
    }

    @Test @Repeat(iterations = 10)
    public void testParser_SelfGenerated() throws Exception {
        Template body = randomBoolean() ? new Template("_body") : null;
        Template path = new Template("_url");
        String host = "test.host";
        String watchId = "_watch";
        String actionId = randomAsciiOfLength(5);

        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, null);

        HttpRequestTemplate request = getHttpRequestTemplate(method, host, TEST_PORT, path, body);
        WebhookAction action = new WebhookAction(request);
        ExecutableWebhookAction executable = new ExecutableWebhookAction(action, logger, ExecuteScenario.Success.client(), templateEngine);

        XContentBuilder builder = jsonBuilder();
        executable.toXContent(builder, ToXContent.EMPTY_PARAMS);

        WebhookActionFactory actionParser = getParser(ExecuteScenario.Success.client());

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        ExecutableWebhookAction parsedExecutable = actionParser.parseExecutable(watchId, actionId, parser);
        assertThat(parsedExecutable, notNullValue());
        assertThat(parsedExecutable.action(), is(action));
    }

    @Test @Repeat(iterations = 10)
    public void testParser_Builder() throws Exception {
        Template body = randomBoolean() ? new Template("_body") : null;
        Template path = new Template("_url");
        String host = "test.host";

        String watchId = "_watch";
        String actionId = randomAsciiOfLength(5);

        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, null);
        HttpRequestTemplate request = getHttpRequestTemplate(method, host, TEST_PORT, path, body);

        WebhookAction action = WebhookAction.builder(request).build();

        XContentBuilder builder = jsonBuilder();
        action.toXContent(builder, ToXContent.EMPTY_PARAMS);

        WebhookActionFactory actionParser = getParser(ExecuteScenario.Success.client());

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        assertThat(parser.nextToken(), is(XContentParser.Token.START_OBJECT));
        ExecutableWebhookAction parsedAction = actionParser.parseExecutable(watchId, actionId, parser);
        assertThat(parsedAction.action(), is(action));
    }

    @Test(expected = ActionException.class)
    public void testParser_Failure() throws Exception {

        XContentBuilder builder = jsonBuilder().startObject().endObject();
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        WebhookActionFactory actionParser = getParser(ExecuteScenario.Success.client());
        //This should fail since we are not supplying a url
        actionParser.parseExecutable("_watch", randomAsciiOfLength(5), parser);
    }

    @Test @Repeat(iterations = 30)
    public void testParser_Result() throws Exception {
        String body = "_body";
        String host = "test.host";
        String path = "/_url";
        String reason = "_reason";
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT);

        Wid wid = new Wid("_watch", randomLong(), DateTime.now());
        String actionId = randomAsciiOfLength(5);

        HttpRequest request = HttpRequest.builder(host, 123)
                .path(path)
                .body(body)
                .method(method)
                .build();

        HttpResponse response = new HttpResponse(randomIntBetween(200, 599), randomAsciiOfLength(10).getBytes(UTF8));

        boolean error = randomBoolean();

        boolean success = !error && response.status() < 400;

        HttpClient client = ExecuteScenario.Success.client();

        WebhookActionFactory actionParser = getParser(client);


        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(WebhookAction.Field.SUCCESS.getPreferredName(), success);
        if (!error) {
            builder.field(WebhookAction.Field.REQUEST.getPreferredName(), request);
            builder.field(WebhookAction.Field.RESPONSE.getPreferredName(), response);
        } else {
            builder.field(WebhookAction.Field.REASON.getPreferredName(), reason);
        }
        builder.endObject();

        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        WebhookAction.Result result = actionParser.parseResult(wid, actionId, parser);

        assertThat(result.success(), equalTo(success));
        if (!error) {
            assertThat(result, instanceOf(WebhookAction.Result.Executed.class));
            WebhookAction.Result.Executed executedResult = (WebhookAction.Result.Executed) result;
            assertThat(executedResult.request(), equalTo(request));
            assertThat(executedResult.response(), equalTo(response));
        } else {
            assertThat(result, Matchers.instanceOf(WebhookAction.Result.Failure.class));
            WebhookAction.Result.Failure failedResult = (WebhookAction.Result.Failure) result;
            assertThat(failedResult.reason(), equalTo(reason));
        }
    }

    @Test @Repeat(iterations = 5)
    public void testParser_Result_Simulated() throws Exception {
        String body = "_body";
        String host = "test.host";
        String path = "/_url";
        HttpMethod method = randomFrom(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT);

        Wid wid = new Wid("_watch", randomLong(), DateTime.now());
        String actionId = randomAsciiOfLength(5);

        HttpRequest request = HttpRequest.builder(host, 123)
                .path(path)
                .body(body)
                .method(method)
                .build();

        XContentBuilder builder = jsonBuilder()
                .startObject()
                .field(WebhookAction.Field.SUCCESS.getPreferredName(), true)
                .field(WebhookAction.Field.SIMULATED_REQUEST.getPreferredName(), request)
                .endObject();

        HttpClient client = ExecuteScenario.Success.client();

        WebhookActionFactory actionParser = getParser(client);
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();

        WebhookAction.Result result = actionParser.parseResult(wid, actionId, parser);
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

        WebhookAction.Result result = getParser(ExecuteScenario.Success.client())
                .parseResult(wid, actionId, parser);

        assertThat(result, instanceOf(WebhookAction.Result.Simulated.class));
        assertThat(((WebhookAction.Result.Simulated)result).request(), equalTo(request));
    }


    private WebhookActionFactory getParser(HttpClient client) {
        return new WebhookActionFactory(ImmutableSettings.EMPTY, client, new HttpRequest.Parser(authRegistry),
                new HttpRequestTemplate.Parser(authRegistry), templateEngine);
    }

    @Test @Repeat(iterations = 100)
    public void testValidUrls() throws Exception {

        HttpClient httpClient = ExecuteScenario.Success.client();
        HttpMethod method = HttpMethod.POST;
        Template path = new Template("/test_{{ctx.watch_id}}");
        String host = "test.host";
        HttpRequestTemplate requestTemplate = getHttpRequestTemplate(method, host, TEST_PORT, path, testBody);
        WebhookAction action = new WebhookAction(requestTemplate);

        ExecutableWebhookAction webhookAction = new ExecutableWebhookAction(action, logger, httpClient, templateEngine);

        String watchName = "test_url_encode" + randomAsciiOfLength(10);
        Watch watch = createWatch(watchName, mock(ClientProxy.class), "account1");
        WatchExecutionContext ctx = new TriggeredExecutionContext(watch, new DateTime(), new ScheduleTriggerEvent(new DateTime(), new DateTime()));
        WebhookAction.Result result = webhookAction.execute("_id", ctx, new Payload.Simple());
        assertThat(result, Matchers.instanceOf(WebhookAction.Result.Executed.class));
    }

    private Watch createWatch(String watchName, ClientProxy client, final String account) throws AddressException, IOException {
        return WatcherTestUtils.createTestWatch(watchName,
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
            public void assertResult(WebhookAction.Result actionResult) {
                assertThat(actionResult.success(), is(false));
                assertThat(actionResult, instanceOf(WebhookAction.Result.Executed.class));
                WebhookAction.Result.Executed executedActionResult = (WebhookAction.Result.Executed) actionResult;
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
            public void assertResult(WebhookAction.Result actionResult) {
                assertThat(actionResult, instanceOf(WebhookAction.Result.Failure.class));
                WebhookAction.Result.Failure failResult = (WebhookAction.Result.Failure) actionResult;
                assertThat(failResult.success(), is(false));
            }
        },

        Success() {
            @Override
            public HttpClient client() throws IOException{
                HttpClient client = mock(HttpClient.class);
                when(client.execute(any(HttpRequest.class)))
                        .thenReturn(new HttpResponse(randomIntBetween(200,399)));
                return client;
            }

            @Override
            public void assertResult(WebhookAction.Result actionResult) {
                assertThat(actionResult, instanceOf(WebhookAction.Result.Executed.class));
                assertThat(actionResult, instanceOf(WebhookAction.Result.Executed.class));
                WebhookAction.Result.Executed executedActionResult = (WebhookAction.Result.Executed) actionResult;
                assertThat(executedActionResult.response().status(), greaterThanOrEqualTo(200));
                assertThat(executedActionResult.response().status(), lessThanOrEqualTo(399));
                assertThat(executedActionResult.request().body(), equalTo(TEST_BODY_STRING));
                assertThat(executedActionResult.request().path(), equalTo(TEST_PATH_STRING));
            }
        };

        public abstract HttpClient client() throws IOException;

        public abstract void assertResult(WebhookAction.Result result);

    }

}
