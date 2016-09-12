/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.actions.email;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.common.text.TextTemplateEngine;
import org.elasticsearch.xpack.watcher.actions.Action;
import org.elasticsearch.xpack.watcher.execution.WatchExecutionContext;
import org.elasticsearch.xpack.watcher.execution.Wid;
import org.elasticsearch.xpack.common.http.HttpClient;
import org.elasticsearch.xpack.common.http.HttpRequest;
import org.elasticsearch.xpack.common.http.HttpRequestTemplate;
import org.elasticsearch.xpack.common.http.HttpResponse;
import org.elasticsearch.xpack.common.http.auth.HttpAuthRegistry;
import org.elasticsearch.xpack.common.http.auth.basic.BasicAuthFactory;
import org.elasticsearch.xpack.common.secret.Secret;
import org.elasticsearch.xpack.common.text.TextTemplate;
import org.elasticsearch.xpack.watcher.support.xcontent.WatcherParams;
import org.elasticsearch.xpack.watcher.test.AbstractWatcherIntegrationTestCase;
import org.elasticsearch.xpack.watcher.test.MockTextTemplateEngine;
import org.elasticsearch.xpack.watcher.watch.Payload;
import org.elasticsearch.xpack.notification.email.Attachment;
import org.elasticsearch.xpack.notification.email.Authentication;
import org.elasticsearch.xpack.notification.email.DataAttachment;
import org.elasticsearch.xpack.notification.email.Email;
import org.elasticsearch.xpack.notification.email.EmailService;
import org.elasticsearch.xpack.notification.email.EmailTemplate;
import org.elasticsearch.xpack.notification.email.HtmlSanitizer;
import org.elasticsearch.xpack.notification.email.Profile;
import org.elasticsearch.xpack.notification.email.attachment.DataAttachmentParser;
import org.elasticsearch.xpack.notification.email.attachment.EmailAttachmentParser;
import org.elasticsearch.xpack.notification.email.attachment.EmailAttachments;
import org.elasticsearch.xpack.notification.email.attachment.EmailAttachmentsParser;
import org.elasticsearch.xpack.notification.email.attachment.HttpEmailAttachementParser;
import org.elasticsearch.xpack.notification.email.attachment.HttpRequestAttachment;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.Before;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.xpack.watcher.test.WatcherTestUtils.mockExecutionContextBuilder;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class EmailActionTests extends ESTestCase {

    private HttpAuthRegistry registry = new HttpAuthRegistry(singletonMap("basic", new BasicAuthFactory(null)));
    private HttpClient httpClient = mock(HttpClient.class);
    private EmailAttachmentsParser emailAttachmentParser;

    @Before
    public void addEmailAttachmentParsers() {
        Map<String, EmailAttachmentParser> emailAttachmentParsers = new HashMap<>();
        emailAttachmentParsers.put(HttpEmailAttachementParser.TYPE, new HttpEmailAttachementParser(httpClient,
                new HttpRequestTemplate.Parser(registry), new MockTextTemplateEngine()));
        emailAttachmentParsers.put(DataAttachmentParser.TYPE, new DataAttachmentParser());
        emailAttachmentParser = new EmailAttachmentsParser(emailAttachmentParsers);
    }

    public void testExecute() throws Exception {
        final String account = "account1";
        EmailService service = new AbstractWatcherIntegrationTestCase.NoopEmailService() {
            @Override
            public EmailService.EmailSent send(Email email, Authentication auth, Profile profile) {
                return new EmailService.EmailSent(account, email);
            }

            @Override
            public EmailService.EmailSent send(Email email, Authentication auth, Profile profile, String accountName) {
                return new EmailService.EmailSent(account, email);
            }
        };
        TextTemplateEngine engine = mock(TextTemplateEngine.class);
        HtmlSanitizer htmlSanitizer = mock(HtmlSanitizer.class);

        EmailTemplate.Builder emailBuilder = EmailTemplate.builder();
        TextTemplate subject = null;
        if (randomBoolean()) {
            subject = new TextTemplate("_subject");
            emailBuilder.subject(subject);
        }
        TextTemplate textBody = null;
        if (randomBoolean()) {
            textBody = new TextTemplate("_text_body");
            emailBuilder.textBody(textBody);
        }
        TextTemplate htmlBody = null;
        if (randomBoolean()) {
            htmlBody = new TextTemplate("_html_body");
            emailBuilder.htmlBody(htmlBody);
        }
        EmailTemplate email = emailBuilder.build();

        Authentication auth = new Authentication("user", new Secret("passwd".toCharArray()));
        Profile profile = randomFrom(Profile.values());

        DataAttachment dataAttachment = randomDataAttachment();
        EmailAttachments emailAttachments = randomEmailAttachments();

        EmailAction action = new EmailAction(email, account, auth, profile, dataAttachment, emailAttachments);
        ExecutableEmailAction executable = new ExecutableEmailAction(action, logger, service, engine, htmlSanitizer,
                emailAttachmentParser.getParsers());

        Map<String, Object> data = new HashMap<>();
        Payload payload = new Payload.Simple(data);

        Map<String, Object> metadata = MapBuilder.<String, Object>newMapBuilder().put("_key", "_val").map();

        DateTime now = DateTime.now(DateTimeZone.UTC);

        Wid wid = new Wid(randomAsciiOfLength(5), randomLong(), now);
        WatchExecutionContext ctx = mockExecutionContextBuilder("watch1")
                .wid(wid)
                .payload(payload)
                .time("watch1", now)
                .metadata(metadata)
                .buildMock();

        Map<String, Object> triggerModel = new HashMap<>();
        triggerModel.put("triggered_time", now);
        triggerModel.put("scheduled_time", now);
        Map<String, Object> ctxModel = new HashMap<>();
        ctxModel.put("id", ctx.id().value());
        ctxModel.put("watch_id", "watch1");
        ctxModel.put("payload", data);
        ctxModel.put("metadata", metadata);
        ctxModel.put("execution_time", now);
        ctxModel.put("trigger", triggerModel);
        ctxModel.put("vars", emptyMap());
        Map<String, Object> expectedModel = singletonMap("ctx", ctxModel);

        if (subject != null) {
            when(engine.render(subject, expectedModel)).thenReturn(subject.getTemplate());
        }
        if (textBody != null) {
            when(engine.render(textBody, expectedModel)).thenReturn(textBody.getTemplate());
        }
        if (htmlBody != null) {
            when(htmlSanitizer.sanitize(htmlBody.getTemplate())).thenReturn(htmlBody.getTemplate());
            when(engine.render(htmlBody, expectedModel)).thenReturn(htmlBody.getTemplate());
        }

        Action.Result result = executable.execute("_id", ctx, payload);

        assertThat(result, notNullValue());
        assertThat(result, instanceOf(EmailAction.Result.Success.class));
        assertThat(((EmailAction.Result.Success) result).account(), equalTo(account));
        Email actualEmail = ((EmailAction.Result.Success) result).email();
        assertThat(actualEmail.id(), is(wid.value()));
        assertThat(actualEmail, notNullValue());
        assertThat(actualEmail.subject(), is(subject == null ? null : subject.getTemplate()));
        assertThat(actualEmail.textBody(), is(textBody == null ? null : textBody.getTemplate()));
        assertThat(actualEmail.htmlBody(), is(htmlBody == null ? null : htmlBody.getTemplate()));
        if (dataAttachment != null) {
            assertThat(actualEmail.attachments(), hasKey("data"));
        }
    }

    public void testParser() throws Exception {
        TextTemplateEngine engine = mock(TextTemplateEngine.class);
        EmailService emailService = mock(EmailService.class);
        Profile profile = randomFrom(Profile.values());
        Email.Priority priority = randomFrom(Email.Priority.values());
        Email.Address[] to = rarely() ? null : Email.AddressList.parse(randomBoolean() ? "to@domain" : "to1@domain,to2@domain").toArray();
        Email.Address[] cc = rarely() ? null : Email.AddressList.parse(randomBoolean() ? "cc@domain" : "cc1@domain,cc2@domain").toArray();
        Email.Address[] bcc = rarely() ? null : Email.AddressList.parse(
                randomBoolean() ? "bcc@domain" : "bcc1@domain,bcc2@domain").toArray();
        Email.Address[] replyTo = rarely() ? null : Email.AddressList.parse(
                randomBoolean() ? "reply@domain" : "reply1@domain,reply2@domain").toArray();
        TextTemplate subject = randomBoolean() ? new TextTemplate("_subject") : null;
        TextTemplate textBody = randomBoolean() ? new TextTemplate("_text_body") : null;
        TextTemplate htmlBody = randomBoolean() ? new TextTemplate("_text_html") : null;
        DataAttachment dataAttachment = randomDataAttachment();
        XContentBuilder builder = jsonBuilder().startObject()
                .field("account", "_account")
                .field("profile", profile.name())
                .field("user", "_user")
                .field("password", "_passwd")
                .field("from", "from@domain")
                .field("priority", priority.name());
        if (dataAttachment != null) {
            builder.field("attach_data", dataAttachment);
        } else if (randomBoolean()) {
            dataAttachment = DataAttachment.DEFAULT;
            builder.field("attach_data", true);
        } else if (randomBoolean()) {
            builder.field("attach_data", false);
        }

        if (to != null) {
            if (to.length == 1) {
                builder.field("to", to[0]);
            } else {
                builder.array("to", (Object[]) to);
            }
        }
        if (cc != null) {
            if (cc.length == 1) {
                builder.field("cc", cc[0]);
            } else {
                builder.array("cc", (Object[]) cc);
            }
        }
        if (bcc != null) {
            if (bcc.length == 1) {
                builder.field("bcc", bcc[0]);
            } else {
                builder.array("bcc", (Object[]) bcc);
            }
        }
        if (replyTo != null) {
            if (replyTo.length == 1) {
                builder.field("reply_to", replyTo[0]);
            } else {
                builder.array("reply_to", (Object[]) replyTo);
            }
        }
        if (subject != null) {
            if (randomBoolean()) {
                builder.field("subject", subject.getTemplate());
            } else {
                builder.field("subject", subject);
            }
        }
        if (textBody != null && htmlBody == null) {
            if (randomBoolean()) {
                builder.field("body", textBody.getTemplate());
            } else {
                builder.startObject("body");
                if (randomBoolean()) {
                    builder.field("text", textBody.getTemplate());
                } else {
                    builder.field("text", textBody);
                }
                builder.endObject();
            }
        }

        if (textBody != null || htmlBody != null) {
            builder.startObject("body");
            if (textBody != null) {
                if (randomBoolean()) {
                    builder.field("text", textBody.getTemplate());
                } else {
                    builder.field("text", textBody);
                }
            }
            if (htmlBody != null) {
                if (randomBoolean()) {
                    builder.field("html", htmlBody.getTemplate());
                } else {
                    builder.field("html", htmlBody);
                }
            }
            builder.endObject();
        }
        builder.endObject();

        BytesReference bytes = builder.bytes();
        logger.info("email action json [{}]", bytes.utf8ToString());
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();

        ExecutableEmailAction executable = new EmailActionFactory(Settings.EMPTY, emailService, engine,
                emailAttachmentParser)
                .parseExecutable(randomAsciiOfLength(8), randomAsciiOfLength(3), parser);

        assertThat(executable, notNullValue());
        assertThat(executable.action().getAccount(), is("_account"));
        if (dataAttachment == null) {
            assertThat(executable.action().getDataAttachment(), nullValue());
        } else {
            assertThat(executable.action().getDataAttachment(), is(dataAttachment));
        }
        assertThat(executable.action().getAuth(), notNullValue());
        assertThat(executable.action().getAuth().user(), is("_user"));
        assertThat(executable.action().getAuth().password(), is(new Secret("_passwd".toCharArray())));
        assertThat(executable.action().getEmail().priority(), is(new TextTemplate(priority.name())));
        if (to != null) {
            assertThat(executable.action().getEmail().to(), arrayContainingInAnyOrder(addressesToTemplates(to)));
        } else {
            assertThat(executable.action().getEmail().to(), nullValue());
        }
        if (cc != null) {
            assertThat(executable.action().getEmail().cc(), arrayContainingInAnyOrder(addressesToTemplates(cc)));
        } else {
            assertThat(executable.action().getEmail().cc(), nullValue());
        }
        if (bcc != null) {
            assertThat(executable.action().getEmail().bcc(), arrayContainingInAnyOrder(addressesToTemplates(bcc)));
        } else {
            assertThat(executable.action().getEmail().bcc(), nullValue());
        }
        if (replyTo != null) {
            assertThat(executable.action().getEmail().replyTo(), arrayContainingInAnyOrder(addressesToTemplates(replyTo)));
        } else {
            assertThat(executable.action().getEmail().replyTo(), nullValue());
        }
    }

    private static TextTemplate[] addressesToTemplates(Email.Address[] addresses) {
        TextTemplate[] templates = new TextTemplate[addresses.length];
        for (int i = 0; i < templates.length; i++) {
            templates[i] = new TextTemplate(addresses[i].toString());
        }
        return templates;
    }

    public void testParserSelfGenerated() throws Exception {
        EmailService service = mock(EmailService.class);
        TextTemplateEngine engine = mock(TextTemplateEngine.class);
        HtmlSanitizer htmlSanitizer = mock(HtmlSanitizer.class);
        EmailTemplate.Builder emailTemplate = EmailTemplate.builder();
        if (randomBoolean()) {
            emailTemplate.from("from@domain");
        }
        if (randomBoolean()) {
            emailTemplate.to(randomBoolean() ? "to@domain" : "to1@domain,to2@domain");
        }
        if (randomBoolean()) {
            emailTemplate.cc(randomBoolean() ? "cc@domain" : "cc1@domain,cc2@domain");
        }
        if (randomBoolean()) {
            emailTemplate.bcc(randomBoolean() ? "bcc@domain" : "bcc1@domain,bcc2@domain");
        }
        if (randomBoolean()) {
            emailTemplate.replyTo(randomBoolean() ? "reply@domain" : "reply1@domain,reply2@domain");
        }
        if (randomBoolean()) {
            emailTemplate.subject("_subject");
        }
        if (randomBoolean()) {
            emailTemplate.textBody("_text_body");
        }
        if (randomBoolean()) {
            emailTemplate.htmlBody("_html_body");
        }
        EmailTemplate email = emailTemplate.build();
        Authentication auth = randomBoolean() ? null : new Authentication("_user", new Secret("_passwd".toCharArray()));
        Profile profile = randomFrom(Profile.values());
        String account = randomAsciiOfLength(6);
        DataAttachment dataAttachment = randomDataAttachment();
        EmailAttachments emailAttachments = randomEmailAttachments();

        EmailAction action = new EmailAction(email, account, auth, profile, dataAttachment, emailAttachments);
        ExecutableEmailAction executable = new ExecutableEmailAction(action, logger, service, engine, htmlSanitizer,
                emailAttachmentParser.getParsers());

        boolean hideSecrets = randomBoolean();
        ToXContent.Params params = WatcherParams.builder().hideSecrets(hideSecrets).build();

        XContentBuilder builder = jsonBuilder();
        executable.toXContent(builder, params);
        BytesReference bytes = builder.bytes();
        logger.info("{}", bytes.utf8ToString());
        XContentParser parser = JsonXContent.jsonXContent.createParser(bytes);
        parser.nextToken();

        ExecutableEmailAction parsed = new EmailActionFactory(Settings.EMPTY, service, engine, emailAttachmentParser)
                .parseExecutable(randomAsciiOfLength(4), randomAsciiOfLength(10), parser);

        if (!hideSecrets) {
            assertThat(parsed, equalTo(executable));
        } else {
            assertThat(parsed.action().getAccount(), is(executable.action().getAccount()));
            assertThat(parsed.action().getEmail(), is(executable.action().getEmail()));
            if (executable.action().getDataAttachment() == null) {
                assertThat(parsed.action().getDataAttachment(), nullValue());
            } else {
                assertThat(parsed.action().getDataAttachment(), is(executable.action().getDataAttachment()));
            }
            if (auth != null) {
                assertThat(parsed.action().getAuth().user(), is(executable.action().getAuth().user()));
                assertThat(parsed.action().getAuth().password(), nullValue());
                assertThat(executable.action().getAuth().password(), notNullValue());
            }
        }
    }

    public void testParserInvalid() throws Exception {
        EmailService emailService = mock(EmailService.class);
        TextTemplateEngine engine = mock(TextTemplateEngine.class);
        EmailAttachmentsParser emailAttachmentsParser = mock(EmailAttachmentsParser.class);

        XContentBuilder builder = jsonBuilder().startObject().field("unknown_field", "value").endObject();
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        parser.nextToken();
        try {
            new EmailActionFactory(Settings.EMPTY, emailService, engine, emailAttachmentsParser)
                    .parseExecutable(randomAsciiOfLength(3), randomAsciiOfLength(7), parser);
        } catch (ElasticsearchParseException e) {
            assertThat(e.getMessage(), containsString("unexpected string field [unknown_field]"));
        }
    }

    public void testRequestAttachmentGetsAppendedToEmailAttachments() throws Exception {
        String attachmentId = "my_attachment";

        // setup mock response
        Map<String, String[]> headers = new HashMap<>(1);
        headers.put(HttpHeaders.Names.CONTENT_TYPE, new String[]{"plain/text"});
        String content = "My wonderful text";
        HttpResponse mockResponse = new HttpResponse(200, content, headers);
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(mockResponse);

        XContentBuilder builder = jsonBuilder().startObject()
                .startObject("attachments")
                // http attachment
                .startObject(attachmentId)
                .startObject("http")
                .startObject("request")
                .field("host", "localhost")
                .field("port", 443)
                .field("path", "/the/evil/test")
                .endObject()
                .endObject()
                .endObject()
                .endObject()
                .endObject();
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        logger.info("JSON: {}", builder.string());

        parser.nextToken();

        EmailActionFactory emailActionFactory = createEmailActionFactory();
        ExecutableEmailAction executableEmailAction =
                emailActionFactory.parseExecutable(randomAsciiOfLength(3), randomAsciiOfLength(7), parser);

        Action.Result result = executableEmailAction.execute("test", createWatchExecutionContext(), new Payload.Simple());
        assertThat(result, instanceOf(EmailAction.Result.Success.class));

        EmailAction.Result.Success successResult = (EmailAction.Result.Success) result;
        Map<String, Attachment> attachments = successResult.email().attachments();
        assertThat(attachments.keySet(), hasSize(1));
        assertThat(attachments, hasKey(attachmentId));

        Attachment externalAttachment = attachments.get(attachmentId);
        assertThat(externalAttachment.bodyPart(), is(notNullValue()));
        InputStream is = externalAttachment.bodyPart().getInputStream();
        String data = Streams.copyToString(new InputStreamReader(is, StandardCharsets.UTF_8));
        assertThat(data, is(content));
    }

    public void testThatDataAttachmentGetsAttachedWithId() throws Exception {
        String attachmentId = randomAsciiOfLength(10) + ".yml";

        XContentBuilder builder = jsonBuilder().startObject()
                .startObject("attachments")
                .startObject(attachmentId)
                .startObject("data")
                .endObject()
                .endObject()
                .endObject()
                .endObject();
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());
        logger.info("JSON: {}", builder.string());

        parser.nextToken();

        EmailActionFactory emailActionFactory = createEmailActionFactory();
        ExecutableEmailAction executableEmailAction =
                emailActionFactory.parseExecutable(randomAsciiOfLength(3), randomAsciiOfLength(7), parser);

        Action.Result result = executableEmailAction.execute("test", createWatchExecutionContext(), new Payload.Simple());
        assertThat(result, instanceOf(EmailAction.Result.Success.class));

        EmailAction.Result.Success successResult = (EmailAction.Result.Success) result;
        Map<String, Attachment> attachments = successResult.email().attachments();

        assertThat(attachments, hasKey(attachmentId));
        Attachment dataAttachment = attachments.get(attachmentId);
        assertThat(dataAttachment.name(), is(attachmentId));
        assertThat(dataAttachment.type(), is("yaml"));
        assertThat(dataAttachment.contentType(), is("application/yaml"));
    }

    public void testThatOneFailedEmailAttachmentResultsInActionFailure() throws Exception {
        EmailService emailService = new AbstractWatcherIntegrationTestCase.NoopEmailService();
        TextTemplateEngine engine = new MockTextTemplateEngine();
        HttpClient httpClient = mock(HttpClient.class);

        // setup mock response, second one is an error
        Map<String, String[]> headers = new HashMap<>(1);
        headers.put(HttpHeaders.Names.CONTENT_TYPE, new String[]{"plain/text"});
        when(httpClient.execute(any(HttpRequest.class)))
                .thenReturn(new HttpResponse(200, "body", headers))
                .thenReturn(new HttpResponse(403));

        // setup email attachment parsers
        HttpRequestTemplate.Parser httpRequestTemplateParser = new HttpRequestTemplate.Parser(registry);
        Map<String, EmailAttachmentParser> attachmentParsers = new HashMap<>();
        attachmentParsers.put(HttpEmailAttachementParser.TYPE, new HttpEmailAttachementParser(httpClient, httpRequestTemplateParser,
                engine));
        EmailAttachmentsParser emailAttachmentsParser = new EmailAttachmentsParser(attachmentParsers);

        XContentBuilder builder = jsonBuilder().startObject()
                .startObject("attachments")
                .startObject("first")
                .startObject("http")
                .startObject("request").field("url", "http://localhost/first").endObject()
                .endObject()
                .endObject()
                .startObject("second")
                .startObject("http")
                .startObject("request").field("url", "http://localhost/second").endObject()
                .endObject()
                .endObject()
                .endObject()
                .endObject();
        XContentParser parser = JsonXContent.jsonXContent.createParser(builder.bytes());

        parser.nextToken();

        ExecutableEmailAction executableEmailAction = new EmailActionFactory(Settings.EMPTY, emailService, engine,
                emailAttachmentsParser).parseExecutable(randomAsciiOfLength(3), randomAsciiOfLength(7), parser);

        DateTime now = DateTime.now(DateTimeZone.UTC);
        Wid wid = new Wid(randomAsciiOfLength(5), randomLong(), now);
        Map<String, Object> metadata = MapBuilder.<String, Object>newMapBuilder().put("_key", "_val").map();
        WatchExecutionContext ctx = mockExecutionContextBuilder("watch1")
                .wid(wid)
                .payload(new Payload.Simple())
                .time("watch1", now)
                .metadata(metadata)
                .buildMock();

        Action.Result result = executableEmailAction.execute("test", ctx, new Payload.Simple());
        assertThat(result, instanceOf(EmailAction.Result.Failure.class));
        EmailAction.Result.Failure failure = (EmailAction.Result.Failure) result;
        assertThat(failure.reason(),
                is("Watch[watch1] attachment[second] HTTP error status host[localhost], port[80], method[GET], path[/second], " +
                        "status[403]"));
    }

    private EmailActionFactory createEmailActionFactory() {
        EmailService emailService = new AbstractWatcherIntegrationTestCase.NoopEmailService();
        TextTemplateEngine engine = mock(TextTemplateEngine.class);

        return new EmailActionFactory(Settings.EMPTY, emailService, engine, emailAttachmentParser);
    }

    private WatchExecutionContext createWatchExecutionContext() {
        DateTime now = DateTime.now(DateTimeZone.UTC);
        Wid wid = new Wid(randomAsciiOfLength(5), randomLong(), now);
        Map<String, Object> metadata = MapBuilder.<String, Object>newMapBuilder().put("_key", "_val").map();
        return mockExecutionContextBuilder("watch1")
                .wid(wid)
                .payload(new Payload.Simple())
                .time("watch1", now)
                .metadata(metadata)
                .buildMock();
    }

    static DataAttachment randomDataAttachment() {
        return randomFrom(DataAttachment.JSON, DataAttachment.YAML, null);
    }

    private EmailAttachments randomEmailAttachments() throws IOException {
        List<EmailAttachmentParser.EmailAttachment> attachments = new ArrayList<>();

        String attachmentType = randomFrom("http", "data", null);
        if ("http".equals(attachmentType)) {
            Map<String, String[]> headers = new HashMap<>(1);
            headers.put(HttpHeaders.Names.CONTENT_TYPE, new String[]{"plain/text"});
            String content = "My wonderful text";
            HttpResponse mockResponse = new HttpResponse(200, content, headers);
            when(httpClient.execute(any(HttpRequest.class))).thenReturn(mockResponse);

            HttpRequestTemplate template = HttpRequestTemplate.builder("localhost", 1234).build();
            attachments.add(new HttpRequestAttachment(randomAsciiOfLength(10), template,
                    randomBoolean(), randomFrom("my/custom-type", null)));
        } else if ("data".equals(attachmentType)) {
            attachments.add(new org.elasticsearch.xpack.notification.email.attachment.DataAttachment(randomAsciiOfLength(10),
                    randomFrom(DataAttachment.JSON, DataAttachment.YAML)));
        }

        return new EmailAttachments(attachments);
    }
}
