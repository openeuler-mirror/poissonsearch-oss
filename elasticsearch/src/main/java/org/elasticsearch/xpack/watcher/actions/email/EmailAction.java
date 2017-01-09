/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.actions.email;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.xpack.common.secret.Secret;
import org.elasticsearch.xpack.notification.email.Authentication;
import org.elasticsearch.xpack.notification.email.DataAttachment;
import org.elasticsearch.xpack.notification.email.Email;
import org.elasticsearch.xpack.notification.email.EmailTemplate;
import org.elasticsearch.xpack.notification.email.Profile;
import org.elasticsearch.xpack.notification.email.attachment.EmailAttachments;
import org.elasticsearch.xpack.notification.email.attachment.EmailAttachmentsParser;
import org.elasticsearch.xpack.watcher.actions.Action;
import org.elasticsearch.xpack.watcher.support.xcontent.WatcherParams;
import org.elasticsearch.xpack.watcher.support.xcontent.WatcherXContentParser;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

public class EmailAction implements Action {

    public static final String TYPE = "email";

    private final EmailTemplate email;
    @Nullable private final String account;
    @Nullable private final Authentication auth;
    @Nullable private final Profile profile;
    @Nullable private final DataAttachment dataAttachment;
    @Nullable private final EmailAttachments emailAttachments;

    public EmailAction(EmailTemplate email, @Nullable String account, @Nullable Authentication auth, @Nullable Profile profile,
                       @Nullable DataAttachment dataAttachment, @Nullable EmailAttachments emailAttachments) {
        this.email = email;
        this.account = account;
        this.auth = auth;
        this.profile = profile;
        this.dataAttachment = dataAttachment;
        this.emailAttachments = emailAttachments;
    }

    public EmailTemplate getEmail() {
        return email;
    }

    public String getAccount() {
        return account;
    }

    public Authentication getAuth() {
        return auth;
    }

    public Profile getProfile() {
        return profile;
    }

    public DataAttachment getDataAttachment() {
        return dataAttachment;
    }

    public EmailAttachments getAttachments() {
        return emailAttachments;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        EmailAction action = (EmailAction) o;

        return Objects.equals(email, action.email) &&
                Objects.equals(account, action.account) &&
                Objects.equals(auth, action.auth) &&
                Objects.equals(profile, action.profile) &&
                Objects.equals(emailAttachments, action.emailAttachments) &&
                Objects.equals(dataAttachment, action.dataAttachment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(email, account, auth, profile, dataAttachment, emailAttachments);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        if (account != null) {
            builder.field(Field.ACCOUNT.getPreferredName(), account);
        }
        if (auth != null) {
            builder.field(Field.USER.getPreferredName(), auth.user());
            if (WatcherParams.hideSecrets(params) == false) {
                builder.field(Field.PASSWORD.getPreferredName(), auth.password(), params);
            }
        }
        if (profile != null) {
            builder.field(Field.PROFILE.getPreferredName(), profile.name().toLowerCase(Locale.ROOT));
        }
        if (dataAttachment != null) {
            builder.field(Field.ATTACH_DATA.getPreferredName(), dataAttachment, params);
        }
        if (emailAttachments != null) {
            emailAttachments.toXContent(builder, params);
        }
        email.xContentBody(builder, params);
        return builder.endObject();
    }

    public static EmailAction parse(String watchId, String actionId, XContentParser parser,
                                    EmailAttachmentsParser emailAttachmentsParser) throws IOException {
        EmailTemplate.Parser emailParser = new EmailTemplate.Parser();
        String account = null;
        String user = null;
        Secret password = null;
        Profile profile = Profile.STANDARD;
        DataAttachment dataAttachment = null;
        EmailAttachments attachments = EmailAttachments.EMPTY_ATTACHMENTS;

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (Field.ATTACH_DATA.match(currentFieldName)) {
                try {
                    dataAttachment = DataAttachment.parse(parser);
                } catch (IOException ioe) {
                    throw new ElasticsearchParseException("could not parse [{}] action [{}/{}]. failed to parse data attachment field " +
                            "[{}]", ioe, TYPE, watchId, actionId, currentFieldName);
                }
            } else if (Field.ATTACHMENTS.match(currentFieldName)) {
                attachments = emailAttachmentsParser.parse(parser);
            } else if (!emailParser.handle(currentFieldName, parser)) {
                if (token == XContentParser.Token.VALUE_STRING) {
                    if (Field.ACCOUNT.match(currentFieldName)) {
                        account = parser.text();
                    } else if (Field.USER.match(currentFieldName)) {
                        user = parser.text();
                    } else if (Field.PASSWORD.match(currentFieldName)) {
                        password = WatcherXContentParser.secretOrNull(parser);
                    } else if (Field.PROFILE.match(currentFieldName)) {
                        try {
                            profile = Profile.resolve(parser.text());
                        } catch (IllegalArgumentException iae) {
                            throw new ElasticsearchParseException("could not parse [{}] action [{}/{}]", TYPE, watchId, actionId, iae);
                        }
                    } else {
                        throw new ElasticsearchParseException("could not parse [{}] action [{}/{}]. unexpected string field [{}]", TYPE,
                                watchId, actionId, currentFieldName);
                    }
                } else {
                    throw new ElasticsearchParseException("could not parse [{}] action [{}/{}]. unexpected token [{}]", TYPE, watchId,
                            actionId, token);
                }
            }
        }

        Authentication auth = null;
        if (user != null) {
            auth = new Authentication(user, password);
        }

        return new EmailAction(emailParser.parsedTemplate(), account, auth, profile, dataAttachment, attachments);
    }

    public static Builder builder(EmailTemplate email) {
        return new Builder(email);
    }

    public abstract static class Result extends Action.Result {

        protected Result(Status status) {
            super(TYPE, status);
        }

        public static class Success extends Result {

            private final String account;
            private final Email email;

            Success(String account, Email email) {
                super(Status.SUCCESS);
                this.account = account;
                this.email = email;
            }

            public String account() {
                return account;
            }

            public Email email() {
                return email;
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                return builder.startObject(type)
                        .field(Field.ACCOUNT.getPreferredName(), account)
                        .field(Field.MESSAGE.getPreferredName(), email, params)
                        .endObject();
            }
        }

        public static class Simulated extends Result {

            private final Email email;

            public Email email() {
                return email;
            }

            Simulated(Email email) {
                super(Status.SIMULATED);
                this.email = email;
            }

            @Override
            public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
                return builder.startObject(type)
                        .field(Field.MESSAGE.getPreferredName(), email, params)
                        .endObject();
            }
        }
    }

    public static class Builder implements Action.Builder<EmailAction> {

        final EmailTemplate email;
        @Nullable String account;
        @Nullable Authentication auth;
        @Nullable Profile profile;
        @Nullable DataAttachment dataAttachment;
        @Nullable EmailAttachments attachments;

        private Builder(EmailTemplate email) {
            this.email = email;
        }

        public Builder setAccount(String account) {
            this.account = account;
            return this;
        }

        public Builder setAuthentication(String username, char[] password) {
            this.auth = new Authentication(username, new Secret(password));
            return this;
        }

        public Builder setProfile(Profile profile) {
            this.profile = profile;
            return this;
        }

        @Deprecated
        public Builder setAttachPayload(DataAttachment dataAttachment) {
            this.dataAttachment = dataAttachment;
            return this;
        }

        public Builder setAttachments(EmailAttachments attachments) {
            this.attachments = attachments;
            return this;
        }

        public EmailAction build() {
            return new EmailAction(email, account, auth, profile, dataAttachment, attachments);
        }
    }

    interface Field extends Action.Field {

        // common fields
        ParseField ACCOUNT = new ParseField("account");

        // action fields
        ParseField PROFILE = new ParseField("profile");
        ParseField USER = new ParseField("user");
        ParseField PASSWORD = new ParseField("password");
        ParseField ATTACH_DATA = new ParseField("attach_data");
        ParseField ATTACHMENTS = new ParseField("attachments");

        // result fields
        ParseField MESSAGE = new ParseField("message");
    }
}
