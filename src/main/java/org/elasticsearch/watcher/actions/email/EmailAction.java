/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.email;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.watcher.actions.Action;
import org.elasticsearch.watcher.actions.ActionSettingsException;
import org.elasticsearch.watcher.actions.email.service.*;
import org.elasticsearch.watcher.support.Variables;
import org.elasticsearch.watcher.support.template.Template;
import org.elasticsearch.watcher.watch.Payload;
import org.elasticsearch.watcher.execution.WatchExecutionContext;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 */
public class EmailAction extends Action<EmailAction.Result> {

    public static final String TYPE = "email";

    final Email emailPrototype;
    final Authentication auth;
    final Profile profile;
    final String account;
    final @Nullable Template subject;
    final @Nullable Template textBody;
    final @Nullable Template htmlBody;
    final boolean attachPayload;

    final EmailService emailService;

    public EmailAction(ESLogger logger, EmailService emailService, Email emailPrototype,
                       Authentication auth, Profile profile, String account, @Nullable Template subject,
                       @Nullable Template textBody, @Nullable Template htmlBody, boolean attachPayload) {

        super(logger);
        this.emailService = emailService;
        this.emailPrototype = emailPrototype;
        this.auth = auth;
        this.profile = profile;
        this.account = account;
        this.subject = subject;
        this.textBody = textBody;
        this.htmlBody = htmlBody;
        this.attachPayload = attachPayload;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    protected Result execute(String actionId, WatchExecutionContext ctx, Payload payload) throws IOException {
        Map<String, Object> model = Variables.createCtxModel(ctx, payload);

        Email.Builder email = Email.builder()
                .id(ctx.id())
                .copyFrom(emailPrototype);

        email.id(ctx.id());
        email.subject(subject != null ? subject.render(model) : "");
        email.textBody(textBody != null ? textBody.render(model) : "");

        if (htmlBody != null) {
            email.htmlBody(htmlBody.render(model));
        }

        if (attachPayload) {
            Attachment.Bytes attachment = new Attachment.XContent.Yaml("payload", "payload.yml", payload);
            email.attach(attachment);
        }

        try {
            if (ctx.simulateAction(actionId)) {
                return new Result.Simulated(email.build());
            } else {
                EmailService.EmailSent sent = emailService.send(email.build(), auth, profile, account);
                return new Result.Success(sent);
            }

        } catch (EmailException ee) {
            logger.error("could not send email [{}] for watch [{}]", ee, actionId, ctx.watch().name());
            return new Result.Failure("could not send email for action [" + actionId +  "]. error: " + ee.getMessage());
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(emailPrototype, auth, profile, account, subject, textBody, htmlBody, attachPayload);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final EmailAction other = (EmailAction) obj;
        return Objects.equals(this.emailPrototype, other.emailPrototype)
                && Objects.equals(this.auth, other.auth)
                && Objects.equals(this.profile, other.profile)
                && Objects.equals(this.account, other.account)
                && Objects.equals(this.subject, other.subject)
                && Objects.equals(this.textBody, other.textBody)
                && Objects.equals(this.htmlBody, other.htmlBody)
                && Objects.equals(this.attachPayload, other.attachPayload);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (account != null) {
            builder.field(Parser.ACCOUNT_FIELD.getPreferredName(), account);
        }
        if (profile != null) {
            builder.field(Parser.PROFILE_FIELD.getPreferredName(), profile);
        }
        if (auth != null) {
            builder.field(Parser.USER_FIELD.getPreferredName(), auth.user());
            builder.field(Parser.PASSWORD_FIELD.getPreferredName(), auth.password());
        }
        builder.field(Parser.ATTACH_PAYLOAD_FIELD.getPreferredName(), attachPayload);
        if (emailPrototype.from() != null) {
            builder.field(Email.FROM_FIELD.getPreferredName(), emailPrototype.from());
        }
        if (emailPrototype.to() != null && !emailPrototype.to().isEmpty()) {
            builder.field(Email.TO_FIELD.getPreferredName(), (ToXContent) emailPrototype.to());
        }
        if (emailPrototype.cc() != null && !emailPrototype.cc().isEmpty()) {
            builder.field(Email.CC_FIELD.getPreferredName(), (ToXContent) emailPrototype.cc());
        }
        if (emailPrototype.bcc() != null && !emailPrototype.bcc().isEmpty()) {
            builder.field(Email.BCC_FIELD.getPreferredName(), (ToXContent) emailPrototype.bcc());
        }
        if (emailPrototype.replyTo() != null && !emailPrototype.replyTo().isEmpty()) {
            builder.field(Email.REPLY_TO_FIELD.getPreferredName(), (ToXContent) emailPrototype.replyTo());
        }
        if (subject != null) {
            builder.field(Email.SUBJECT_FIELD.getPreferredName(), subject);
        }
        if (textBody != null) {
            builder.field(Email.TEXT_BODY_FIELD.getPreferredName(), textBody);
        }
        if (htmlBody != null) {
            builder.field(Email.HTML_BODY_FIELD.getPreferredName(), htmlBody);
        }
        if (emailPrototype.priority() != null) {
            builder.field(Email.PRIORITY_FIELD.getPreferredName(), emailPrototype.priority());
        }
        return builder.endObject();
    }

    public static class Parser extends AbstractComponent implements Action.Parser<Result, EmailAction> {

        public static final ParseField ACCOUNT_FIELD = new ParseField("account");
        public static final ParseField PROFILE_FIELD = new ParseField("profile");
        public static final ParseField USER_FIELD = new ParseField("user");
        public static final ParseField PASSWORD_FIELD = new ParseField("password");
        public static final ParseField ATTACH_PAYLOAD_FIELD = new ParseField("attach_payload");
        public static final ParseField EMAIL_FIELD = new ParseField("email");
        public static final ParseField REASON_FIELD = new ParseField("reason");
        public static final ParseField SIMULATED_EMAIL_FIELD = new ParseField("simulated_email");

        private final Template.Parser templateParser;
        private final EmailService emailService;

        @Inject
        public Parser(Settings settings, EmailService emailService, Template.Parser templateParser) {
            super(settings);
            this.emailService = emailService;
            this.templateParser = templateParser;
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public EmailAction parse(XContentParser parser) throws IOException {
            String user = null;
            String password = null;
            String account = null;
            Profile profile = null;
            Email.Builder email = Email.builder().id("prototype");
            Template subject = null;
            Template textBody = null;
            Template htmlBody = null;
            boolean attachPayload = false;

            String currentFieldName = null;
            XContentParser.Token token;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if ((token.isValue() || token == XContentParser.Token.START_OBJECT || token == XContentParser.Token.START_ARRAY) && currentFieldName != null) {
                    if (Email.FROM_FIELD.match(currentFieldName)) {
                        email.from(Email.Address.parse(currentFieldName, token, parser));
                    } else if (Email.REPLY_TO_FIELD.match(currentFieldName)) {
                        email.replyTo(Email.AddressList.parse(currentFieldName, token, parser));
                    } else if (Email.TO_FIELD.match(currentFieldName)) {
                        email.to(Email.AddressList.parse(currentFieldName, token, parser));
                    } else if (Email.CC_FIELD.match(currentFieldName)) {
                        email.cc(Email.AddressList.parse(currentFieldName, token, parser));
                    } else if (Email.BCC_FIELD.match(currentFieldName)) {
                        email.bcc(Email.AddressList.parse(currentFieldName, token, parser));
                    } else if (Email.SUBJECT_FIELD.match(currentFieldName)) {
                        try {
                            subject = templateParser.parse(parser);
                        } catch (Template.Parser.ParseException pe) {
                            throw new ActionSettingsException("could not parse email [subject] template", pe);
                        }
                    } else if (Email.TEXT_BODY_FIELD.match(currentFieldName)) {
                        try {
                            textBody = templateParser.parse(parser);
                        } catch (Template.Parser.ParseException pe) {
                            throw new ActionSettingsException("could not parse email [text_body] template", pe);
                        }
                    } else if (Email.HTML_BODY_FIELD.match(currentFieldName)) {
                        try {
                            htmlBody = templateParser.parse(parser);
                        } catch (Template.Parser.ParseException pe) {
                            throw new ActionSettingsException("could not parse email [html_body] template", pe);
                        }
                    } else if (token == XContentParser.Token.VALUE_STRING) {
                        if (Email.PRIORITY_FIELD.match(currentFieldName)) {
                            email.priority(Email.Priority.resolve(parser.text()));
                        }  else if (ACCOUNT_FIELD.match(currentFieldName)) {
                            account = parser.text();
                        } else if (USER_FIELD.match(currentFieldName)) {
                            user = parser.text();
                        } else if (PASSWORD_FIELD.match(currentFieldName)) {
                            password = parser.text();
                        } else if (PROFILE_FIELD.match(currentFieldName)) {
                            profile = Profile.resolve(parser.text());
                        } else {
                            throw new ActionSettingsException("could not parse email action. unrecognized string field [" + currentFieldName + "]");
                        }
                    } else if (token == XContentParser.Token.VALUE_BOOLEAN) {
                        if (ATTACH_PAYLOAD_FIELD.match(currentFieldName)) {
                            attachPayload = parser.booleanValue();
                        } else {
                            throw new ActionSettingsException("could not parse email action. unrecognized boolean field [" + currentFieldName + "]");
                        }
                    } else {
                        throw new ActionSettingsException("could not parse email action. unexpected token [" + token + "]");
                    }
                }
            }

            Authentication auth = user != null ? new Authentication(user, password) : null;

            return new EmailAction(logger, emailService, email.build(), auth, profile, account, subject, textBody, htmlBody, attachPayload);
        }

        @Override
        public EmailAction.Result parseResult(XContentParser parser) throws IOException {
            Boolean success = null;
            Email email = null;
            Email simulatedEmail = null;
            String account = null;
            String reason = null;

            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (token.isValue()) {
                    if (ACCOUNT_FIELD.match(currentFieldName)) {
                        account = parser.text();
                    } else if (REASON_FIELD.match(currentFieldName)) {
                        reason = parser.text();
                    } else if (token == XContentParser.Token.VALUE_BOOLEAN) {
                        if (Action.Result.SUCCESS_FIELD.match(currentFieldName)) {
                            success = parser.booleanValue();
                        } else {
                            throw new EmailException("could not parse email result. unexpected field [" + currentFieldName + "]");
                        }
                    } else {
                        throw new EmailException("could not parse email result. unexpected field [" + currentFieldName + "]");
                    }
                } else if (token == XContentParser.Token.START_OBJECT) {
                    if (EMAIL_FIELD.match(currentFieldName)) {
                        email = Email.parse(parser);
                    } else if (SIMULATED_EMAIL_FIELD.match(currentFieldName)) {
                        simulatedEmail = Email.parse(parser);
                    } else {
                        throw new EmailException("could not parse email result. unexpected field [" + currentFieldName + "]");
                    }
                } else {
                    throw new EmailException("could not parse email result. unexpected token [" + token + "]");
                }
            }

            if (simulatedEmail != null) {
                return new Result.Simulated(simulatedEmail);
            }

            if (success == null) {
                throw new EmailException("could not parse email result. expected field [success]");
            }

            return success ? new Result.Success(new EmailService.EmailSent(account, email)) : new Result.Failure(reason);
        }
    }

    public static abstract class Result extends Action.Result {

        public Result(String type, boolean success) {
            super(type, success);
        }

        public static class Success extends Result {

            private final EmailService.EmailSent sent;

            private Success(EmailService.EmailSent sent) {
                super(TYPE, true);
                this.sent = sent;
            }

            @Override
            public XContentBuilder xContentBody(XContentBuilder builder, Params params) throws IOException {
                return builder.field(Parser.ACCOUNT_FIELD.getPreferredName(), sent.account())
                        .field(Parser.EMAIL_FIELD.getPreferredName(), sent.email());
            }

            public String account() {
                return sent.account();
            }

            public Email email() {
                return sent.email();
            }
        }

        public static class Failure extends Result {

            private final String reason;

            public Failure(String reason) {
                super(TYPE, false);
                this.reason = reason;
            }

            public String reason() {
                return reason;
            }

            @Override
            protected XContentBuilder xContentBody(XContentBuilder builder, Params params) throws IOException {
                return builder.field(Parser.REASON_FIELD.getPreferredName(), reason);
            }
        }

        public static class Simulated extends Result {

            private final Email email;

            public Email email() {
                return email;
            }

            public Simulated(Email email) {
                super(TYPE, true);
                this.email = email;
            }

            @Override
            protected XContentBuilder xContentBody(XContentBuilder builder, Params params) throws IOException {
                return builder.field(Parser.SIMULATED_EMAIL_FIELD.getPreferredName(), email);
            }
        }
    }

    public static class SourceBuilder extends Action.SourceBuilder<SourceBuilder> {

        private Email.Address from;
        private Email.AddressList replyTo;
        private Email.AddressList to;
        private Email.AddressList cc;
        private Email.AddressList bcc;
        private Authentication auth = null;
        private Profile profile = null;
        private String account = null;
        private Template subject;
        private Template textBody;
        private Template htmlBody;
        private Boolean attachPayload;

        public SourceBuilder(String id) {
            super(id);
        }

        @Override
        public String type() {
            return TYPE;
        }

        @Override
        public XContentBuilder actionXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            if (from != null) {
                builder.field(Email.FROM_FIELD.getPreferredName(), from);
            }
            if (replyTo != null && replyTo.size() != 0) {
                builder.field(Email.REPLY_TO_FIELD.getPreferredName(), (ToXContent) replyTo);
            }
            if (to != null && to.size() != 0) {
                builder.field(Email.TO_FIELD.getPreferredName(), (ToXContent) to);
            }
            if (cc != null && cc.size() != 0) {
                builder.field(Email.CC_FIELD.getPreferredName(), (ToXContent) cc);
            }
            if (bcc != null && bcc.size() != 0) {
                builder.field(Email.BCC_FIELD.getPreferredName(), (ToXContent) bcc);
            }
            if (auth != null) {
                builder.field(Parser.USER_FIELD.getPreferredName(), auth.user());
                builder.field(Parser.PASSWORD_FIELD.getPreferredName(), auth.password());
            }
            if (profile != null) {
                builder.field(Parser.PROFILE_FIELD.getPreferredName(), profile);
            }
            if (account != null) {
                builder.field(Parser.ACCOUNT_FIELD.getPreferredName(), account);
            }
            if (subject != null) {
                builder.field(Email.SUBJECT_FIELD.getPreferredName(), subject);
            }
            if (textBody != null) {
                builder.field(Email.TEXT_BODY_FIELD.getPreferredName(), textBody);
            }
            if (htmlBody != null) {
                builder.field(Email.HTML_BODY_FIELD.getPreferredName(), htmlBody);
            }
            if (attachPayload != null) {
                builder.field(Parser.ATTACH_PAYLOAD_FIELD.getPreferredName(), attachPayload);
            }
            return builder.endObject();
        }
    }

}
