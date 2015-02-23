/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts.actions.email.service;

import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.inject.Provider;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.junit.Ignore;

import java.io.IOException;
import java.io.InputStream;

/**
 *
 */
@Ignore
public class ManualPublicSmtpServersTests {

    private static final Terminal terminal = Terminal.DEFAULT;

    public static class Gmail {

        public static void main(String[] args) throws Exception {
            test(Profile.GMAIL, ImmutableSettings.builder()
                    .put("alerts.actions.email.service.account.gmail.smtp.auth", true)
                    .put("alerts.actions.email.service.account.gmail.smtp.starttls.enable", true)
                    .put("alerts.actions.email.service.account.gmail.smtp.host", "smtp.gmail.com")
                    .put("alerts.actions.email.service.account.gmail.smtp.port", 587)
                    .put("alerts.actions.email.service.account.gmail.smtp.user", terminal.readText("username: "))
                    .put("alerts.actions.email.service.account.gmail.smtp.password", new String(terminal.readSecret("password: ")))
                    .put("alerts.actions.email.service.account.gmail.email_defaults.to", terminal.readText("to: "))
            );

        }
    }

    public static class OutlookDotCom {

        public static void main(String[] args) throws Exception {
            test(Profile.STANDARD, ImmutableSettings.builder()
                    .put("alerts.actions.email.service.account.outlook.smtp.auth", true)
                    .put("alerts.actions.email.service.account.outlook.smtp.starttls.enable", true)
                    .put("alerts.actions.email.service.account.outlook.smtp.host", "smtp-mail.outlook.com")
                    .put("alerts.actions.email.service.account.outlook.smtp.port", 587)
                    .put("alerts.actions.email.service.account.outlook.smtp.user", "elastic.user@outlook.com")
                    .put("alerts.actions.email.service.account.outlook.smtp.password", "fantastic42")
                    .put("alerts.actions.email.service.account.outlook.email_defaults.to", "elastic.user@outlook.com")
                    .put()
            );
        }
    }

    public static class YahooMail {

        public static void main(String[] args) throws Exception {
            test(Profile.STANDARD, ImmutableSettings.builder()
                            .put("alerts.actions.email.service.account.yahoo.smtp.starttls.enable", true)
                            .put("alerts.actions.email.service.account.yahoo.smtp.auth", true)
                            .put("alerts.actions.email.service.account.yahoo.smtp.host", "smtp.mail.yahoo.com")
                            .put("alerts.actions.email.service.account.yahoo.smtp.port", 587)
                            .put("alerts.actions.email.service.account.yahoo.smtp.user", "elastic.user@yahoo.com")
                            .put("alerts.actions.email.service.account.yahoo.smtp.password", "fantastic42")
                            // note: from must be set to the same authenticated user account
                            .put("alerts.actions.email.service.account.yahoo.email_defaults.from", "elastic.user@yahoo.com")
                            .put("alerts.actions.email.service.account.yahoo.email_defaults.to", "elastic.user@yahoo.com")
            );
        }
    }

    static void test(Profile profile, Settings.Builder builder) throws Exception {
        InternalEmailService service = startEmailService(builder);
        try {

            ToXContent content = new ToXContent() {
                @Override
                public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                    return builder.startObject()
                            .field("key1", "value1")
                            .field("key2", "value2")
                            .field("key3", "value3")
                            .endObject();
                }
            };

            Email email = Email.builder()
                    .id("_id")
                    .subject("_subject")
                    .textBody("_text_body")
                    .htmlBody("<b>html body</b><p/><p/><img src=\"cid:logo\"/>")
                    .attach(new Attachment.XContent.Yaml("test.yml", content))
                    .inline(new Inline.Stream("logo", "logo.jpg", new Provider<InputStream>() {
                        @Override
                        public InputStream get() {
                            return InternalEmailServiceTests.class.getResourceAsStream("logo.jpg");
                        }
                    }))
                    .build();

            EmailService.EmailSent sent = service.send(email, null, profile);

            terminal.println("email sent via account [%s]", sent.account());
        } finally {
            service.stop();
        }
    }

    static InternalEmailService startEmailService(Settings.Builder builder) {
        Settings settings = builder.build();
        InternalEmailService service = new InternalEmailService(settings, new NodeSettingsService(settings));
        service.start();
        return service;
    }
}
