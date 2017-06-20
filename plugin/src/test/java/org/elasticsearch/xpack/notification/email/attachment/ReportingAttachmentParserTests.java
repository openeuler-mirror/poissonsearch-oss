/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.notification.email.attachment;

import com.fasterxml.jackson.core.io.JsonEOFException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.common.http.HttpClient;
import org.elasticsearch.xpack.common.http.HttpMethod;
import org.elasticsearch.xpack.common.http.HttpProxy;
import org.elasticsearch.xpack.common.http.HttpRequest;
import org.elasticsearch.xpack.common.http.HttpResponse;
import org.elasticsearch.xpack.common.http.auth.HttpAuth;
import org.elasticsearch.xpack.common.http.auth.HttpAuthFactory;
import org.elasticsearch.xpack.common.http.auth.HttpAuthRegistry;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuth;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuthFactory;
import org.elasticsearch.xpack.common.text.TextTemplate;
import org.elasticsearch.xpack.common.text.TextTemplateEngine;
import org.elasticsearch.xpack.notification.email.Attachment;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.execution.Wid;
import org.elasticsearch.xpack.watcher.test.MockTextTemplateEngine;
import org.elasticsearch.xpack.watcher.watch.Payload;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.mockExecutionContextBuilder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ReportingAttachmentParserTests extends ESTestCase {

    private HttpClient httpClient;
    private Map<String, EmailAttachmentParser> attachmentParsers = new HashMap<>();
    private EmailAttachmentsParser emailAttachmentsParser;
    private ReportingAttachmentParser reportingAttachmentParser;
    private HttpAuthRegistry authRegistry;
    private MockTextTemplateEngine templateEngine = new MockTextTemplateEngine();
    private String dashboardUrl = "http://www.example.org/ovb/api/reporting/generate/dashboard/My-Dashboard";

    @Before
    public void init() throws Exception {
        httpClient = mock(HttpClient.class);

        Map<String, HttpAuthFactory> factories = MapBuilder.<String, HttpAuthFactory>newMapBuilder()
                .put("basic", new BasicAuthFactory(null))
                .immutableMap();
        authRegistry = new HttpAuthRegistry(factories);
        reportingAttachmentParser = new ReportingAttachmentParser(Settings.EMPTY, httpClient, templateEngine, authRegistry);

        attachmentParsers.put(ReportingAttachmentParser.TYPE, reportingAttachmentParser);
        emailAttachmentsParser = new EmailAttachmentsParser(attachmentParsers);
    }

    public void testSerializationWorks() throws Exception {
        String id = "some-id";

        XContentBuilder builder = jsonBuilder().startObject().startObject(id)
                .startObject(ReportingAttachmentParser.TYPE)
                .field("url", dashboardUrl);

        Integer retries = null;
        boolean withRetries = randomBoolean();
        if (withRetries) {
            retries = randomIntBetween(1, 10);
            builder.field("retries", retries);
        }

        TimeValue interval = null;
        boolean withInterval = randomBoolean();
        if (withInterval) {
            builder.field("interval", "1s");
            interval = TimeValue.timeValueSeconds(1);
        }

        boolean isInline = randomBoolean();
        if (isInline) {
            builder.field("inline", true);
        }

        HttpAuth auth = null;
        boolean withAuth = randomBoolean();
        if (withAuth) {
            builder.startObject("auth").startObject("basic")
                    .field("username", "foo")
                    .field("password", "secret")
                    .endObject().endObject();
            auth = new BasicAuth("foo", "secret".toCharArray());
        }

        HttpProxy proxy = null;
        boolean withProxy = randomBoolean();
        if (withProxy) {
            proxy = new HttpProxy("example.org", 8080);
            builder.startObject("proxy")
                    .field("host", proxy.getHost())
                    .field("port", proxy.getPort())
                    .endObject();
        }

        builder.endObject().endObject().endObject();
        XContentParser parser = createParser(builder);

        EmailAttachments emailAttachments = emailAttachmentsParser.parse(parser);
        assertThat(emailAttachments.getAttachments(), hasSize(1));

        XContentBuilder toXcontentBuilder = jsonBuilder().startObject();
        List<EmailAttachmentParser.EmailAttachment> attachments = new ArrayList<>(emailAttachments.getAttachments());
        attachments.get(0).toXContent(toXcontentBuilder, ToXContent.EMPTY_PARAMS);
        toXcontentBuilder.endObject();
        assertThat(toXcontentBuilder.string(), is(builder.string()));

        XContentBuilder attachmentXContentBuilder = jsonBuilder().startObject();
        ReportingAttachment attachment = new ReportingAttachment(id, dashboardUrl, isInline, interval, retries, auth, proxy);
        attachment.toXContent(attachmentXContentBuilder, ToXContent.EMPTY_PARAMS);
        attachmentXContentBuilder.endObject();
        assertThat(attachmentXContentBuilder.string(), is(builder.string()));

        assertThat(attachments.get(0).inline(), is(isInline));
    }

    public void testGoodCase() throws Exception {
        // returns interval HTTP code for five times, then return expected data
        String content = randomAlphaOfLength(200);
        String path = "/ovb/api/reporting/jobs/download/iu5zfzvk15oa8990bfas9wy2";
        String randomContentType = randomAlphaOfLength(20);
        Map<String, String[]> headers = new HashMap<>();
        headers.put("Content-Type", new String[] { randomContentType });
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\""+ path +"\", \"other\":\"content\"}"))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(200, content, headers));

        ReportingAttachment reportingAttachment =
                new ReportingAttachment("foo", dashboardUrl, randomBoolean(), TimeValue.timeValueMillis(1), 10, null, null);
        Attachment attachment = reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, reportingAttachment);
        assertThat(attachment, instanceOf(Attachment.Bytes.class));
        Attachment.Bytes bytesAttachment = (Attachment.Bytes) attachment;
        assertThat(new String(bytesAttachment.bytes(), StandardCharsets.UTF_8), is(content));
        assertThat(bytesAttachment.contentType(), is(randomContentType));

        ArgumentCaptor<HttpRequest> requestArgumentCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(7)).execute(requestArgumentCaptor.capture());
        assertThat(requestArgumentCaptor.getAllValues(), hasSize(7));
        // first invocation to the original URL
        assertThat(requestArgumentCaptor.getAllValues().get(0).path(), is("/ovb/api/reporting/generate/dashboard/My-Dashboard"));
        assertThat(requestArgumentCaptor.getAllValues().get(0).method(), is(HttpMethod.POST));
        // all other invocations to the redirected urls from the JSON payload
        for (int i = 1; i < 7; i++) {
            assertThat(requestArgumentCaptor.getAllValues().get(i).path(), is(path));
            assertThat(requestArgumentCaptor.getAllValues().get(i).params().keySet(), hasSize(0));
        }

        // test that the header "kbn-xsrf" has been set to "reporting" in all requests
        requestArgumentCaptor.getAllValues().forEach((req) -> assertThat(req.headers(), hasEntry("kbn-xsrf", "reporting")));
    }

    public void testInitialRequestFailsWithError() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(403));
        ReportingAttachment attachment = new ReportingAttachment("foo", dashboardUrl, randomBoolean(), null, null, null, null);

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("Error response when trying to trigger reporting generation"));
    }

    public void testInitialRequestThrowsIOException() throws Exception {
        when(httpClient.execute(any(HttpRequest.class))).thenThrow(new IOException("Connection timed out"));
        ReportingAttachment attachment = new ReportingAttachment("foo", "http://www.example.org/", randomBoolean(), null, null, null, null);
        IOException e = expectThrows(IOException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("Connection timed out"));
    }

    public void testInitialRequestContainsInvalidPayload() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                // closing json bracket is missing
                .thenReturn(new HttpResponse(200, "{\"path\":\"anything\""));
        ReportingAttachment attachment = new ReportingAttachment("foo", dashboardUrl, randomBoolean(), null, null, null, null);
        JsonEOFException e = expectThrows(JsonEOFException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("Unexpected end-of-input"));
    }

    public void testInitialRequestContainsPathAsObject() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                // closing json bracket is missing
                .thenReturn(new HttpResponse(200, "{\"path\": { \"foo\" : \"anything\"}}"));
        ReportingAttachment attachment = new ReportingAttachment("foo", "http://www.example.org/", randomBoolean(), null, null, null, null);
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(),
                containsString("[reporting_attachment_kibana_payload] path doesn't support values of type: START_OBJECT"));
    }

    public void testInitialRequestDoesNotContainPathInJson() throws Exception {
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(new HttpResponse(200, "{\"foo\":\"bar\"}"));
        ReportingAttachment attachment = new ReportingAttachment("foo", dashboardUrl, randomBoolean(), null, null, null, null);
        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("Watch[watch1] reporting[foo] field path found in JSON payload"));
    }

    public void testPollingRequestIsError() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenReturn(new HttpResponse(403));

        ReportingAttachment attachment =
                new ReportingAttachment("foo", "http://www.example.org/", randomBoolean(), TimeValue.timeValueMillis(1), 10, null, null);

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("Error when polling pdf"));
    }

    public void testPollingRequestRetryIsExceeded() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(503));

        ReportingAttachment attachment =
                new ReportingAttachment("foo", "http://www.example.org/", randomBoolean(), TimeValue.timeValueMillis(1), 1, null, null);

        ElasticsearchException e = expectThrows(ElasticsearchException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("Aborting due to maximum number of retries hit [1]"));
    }

    public void testPollingRequestUnknownHTTPError() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenReturn(new HttpResponse(1));

        ReportingAttachment attachment =
                new ReportingAttachment("foo", "http://www.example.org/", randomBoolean(), TimeValue.timeValueMillis(1), null, null, null);

        IllegalStateException e = expectThrows(IllegalStateException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("Unexpected status code"));
    }

    public void testPollingRequestIOException() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenThrow(new IOException("whatever"));

        ReportingAttachment attachment =
                new ReportingAttachment("foo", "http://www.example.org/", randomBoolean(), TimeValue.timeValueMillis(1), null, null, null);

        IOException e = expectThrows(IOException.class,
                () -> reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));
        assertThat(e.getMessage(), containsString("whatever"));
    }

    public void testWithBasicAuth() throws Exception {
        String content = randomAlphaOfLength(200);
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(200, content));

        ReportingAttachment attachment = new ReportingAttachment("foo", dashboardUrl, randomBoolean(),
                TimeValue.timeValueMillis(1), 10, new BasicAuth("foo", "bar".toCharArray()), null);

        reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment);

        ArgumentCaptor<HttpRequest> requestArgumentCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(3)).execute(requestArgumentCaptor.capture());
        List<HttpRequest> allRequests = requestArgumentCaptor.getAllValues();
        assertThat(allRequests, hasSize(3));
        for (HttpRequest request : allRequests) {
            assertThat(request.auth(), is(notNullValue()));
            assertThat(request.auth().type(), is("basic"));
            assertThat(request.auth(), instanceOf(BasicAuth.class));
            BasicAuth basicAuth = (BasicAuth) request.auth();
            assertThat(basicAuth.getUsername(), is("foo"));
        }
    }

    public void testPollingDefaultsRetries() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenReturn(new HttpResponse(503));

        ReportingAttachment attachment = new ReportingAttachment("foo", dashboardUrl, randomBoolean(), TimeValue.timeValueMillis(1),
                ReportingAttachmentParser.RETRIES_SETTING.getDefault(Settings.EMPTY), new BasicAuth("foo", "bar".toCharArray()), null);
        expectThrows(ElasticsearchException.class, () ->
                reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));

        verify(httpClient, times(ReportingAttachmentParser.RETRIES_SETTING.getDefault(Settings.EMPTY) + 1)).execute(any());
    }

    public void testPollingDefaultCanBeOverriddenBySettings() throws Exception {
        int retries = 10;
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenReturn(new HttpResponse(503));

        ReportingAttachment attachment = new ReportingAttachment("foo", dashboardUrl, randomBoolean(), null, null, null, null);

        Settings settings = Settings.builder()
                .put(ReportingAttachmentParser.INTERVAL_SETTING.getKey(), "1ms")
                .put(ReportingAttachmentParser.RETRIES_SETTING.getKey(), retries)
                .build();

        reportingAttachmentParser = new ReportingAttachmentParser(settings, httpClient, templateEngine, authRegistry);
        expectThrows(ElasticsearchException.class, () ->
                reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment));

        verify(httpClient, times(retries + 1)).execute(any());
    }

    public void testThatUrlIsTemplatable() throws Exception {
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "{\"path\":\"whatever\"}"))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(200, randomAlphaOfLength(10)));

        TextTemplateEngine replaceHttpWithHttpsTemplateEngine = new TextTemplateEngine(Settings.EMPTY, null) {
            @Override
            public String render(TextTemplate textTemplate, Map<String, Object> model) {
                return textTemplate.getTemplate().replaceAll("REPLACEME", "REPLACED");
            }
        };

        ReportingAttachment attachment = new ReportingAttachment("foo", "http://www.example.org/REPLACEME", randomBoolean(),
                TimeValue.timeValueMillis(1), 10, new BasicAuth("foo", "bar".toCharArray()), null);
        reportingAttachmentParser = new ReportingAttachmentParser(Settings.EMPTY, httpClient,
                replaceHttpWithHttpsTemplateEngine, authRegistry);
        reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, attachment);

        ArgumentCaptor<HttpRequest> requestArgumentCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient, times(3)).execute(requestArgumentCaptor.capture());

        List<String> paths = requestArgumentCaptor.getAllValues().stream().map(HttpRequest::path).collect(Collectors.toList());
        assertThat(paths, not(hasItem(containsString("REPLACEME"))));
    }

    public void testRetrySettingCannotBeNegative() throws Exception {
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () ->
                new ReportingAttachment("foo", "http://www.example.org/REPLACEME", randomBoolean(), null, -10, null, null));
        assertThat(e.getMessage(), is("Retries for attachment must be >= 0"));

        Settings invalidSettings = Settings.builder().put("xpack.notification.reporting.retries", -10).build();
        e = expectThrows(IllegalArgumentException.class,
                () -> new ReportingAttachmentParser(invalidSettings, httpClient, templateEngine, authRegistry));
        assertThat(e.getMessage(), is("Failed to parse value [-10] for setting [xpack.notification.reporting.retries] must be >= 0"));
    }

    public void testHttpProxy() throws Exception {
        String content = randomAlphaOfLength(200);
        String path = "/ovb/api/reporting/jobs/download/iu5zfzvk15oa8990bfas9wy2";
        String randomContentType = randomAlphaOfLength(20);
        Map<String, String[]> headers = new HashMap<>();
        headers.put("Content-Type", new String[] { randomContentType });
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        when(httpClient.execute(requestCaptor.capture()))
                .thenReturn(new HttpResponse(200, "{\"path\":\""+ path +"\", \"other\":\"content\"}"))
                .thenReturn(new HttpResponse(503))
                .thenReturn(new HttpResponse(200, content, headers));

        HttpProxy proxy = new HttpProxy("localhost", 8080);
        ReportingAttachment reportingAttachment =
                new ReportingAttachment("foo", "http://www.example.org/", randomBoolean(), TimeValue.timeValueMillis(1), null, null, proxy);

        reportingAttachmentParser.toAttachment(createWatchExecutionContext(), Payload.EMPTY, reportingAttachment);

        assertThat(requestCaptor.getAllValues(), hasSize(3));
        requestCaptor.getAllValues().forEach(req -> assertThat(req.proxy(), is(proxy)));
    }

    private WatchExecutionContext createWatchExecutionContext() {
        DateTime now = DateTime.now(DateTimeZone.UTC);
        return mockExecutionContextBuilder("watch1")
                .wid(new Wid(randomAlphaOfLength(5), now))
                .payload(new Payload.Simple())
                .time("watch1", now)
                .metadata(Collections.emptyMap())
                .buildMock();
    }
}
