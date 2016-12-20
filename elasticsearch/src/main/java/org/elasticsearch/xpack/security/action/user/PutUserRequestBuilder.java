/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.action.user;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.support.WriteRequestBuilder;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentParser.Token;
import org.elasticsearch.xpack.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.support.Validation;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.xpack.common.xcontent.XContentUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

public class PutUserRequestBuilder extends ActionRequestBuilder<PutUserRequest, PutUserResponse, PutUserRequestBuilder>
        implements WriteRequestBuilder<PutUserRequestBuilder> {

    private final Hasher hasher = Hasher.BCRYPT;

    public PutUserRequestBuilder(ElasticsearchClient client) {
        this(client, PutUserAction.INSTANCE);
    }

    public PutUserRequestBuilder(ElasticsearchClient client, PutUserAction action) {
        super(client, action, new PutUserRequest());
    }

    public PutUserRequestBuilder username(String username) {
        request.username(username);
        return this;
    }

    public PutUserRequestBuilder roles(String... roles) {
        request.roles(roles);
        return this;
    }

    public PutUserRequestBuilder password(@Nullable char[] password) {
        if (password != null) {
            Validation.Error error = Validation.Users.validatePassword(password);
            if (error != null) {
                ValidationException validationException = new ValidationException();
                validationException.addValidationError(error.toString());
                throw validationException;
            }
            request.passwordHash(hasher.hash(new SecuredString(password)));
        } else {
            request.passwordHash(null);
        }
        return this;
    }

    public PutUserRequestBuilder metadata(Map<String, Object> metadata) {
        request.metadata(metadata);
        return this;
    }

    public PutUserRequestBuilder fullName(String fullName) {
        request.fullName(fullName);
        return this;
    }

    public PutUserRequestBuilder email(String email) {
        request.email(email);
        return this;
    }

    public PutUserRequestBuilder passwordHash(char[] passwordHash) {
        request.passwordHash(passwordHash);
        return this;
    }

    public PutUserRequestBuilder enabled(boolean enabled) {
        request.enabled(enabled);
        return this;
    }

    public PutUserRequestBuilder source(String username, BytesReference source) throws IOException {
        username(username);
        // EMPTY is ok here because we never call namedObject
        try (XContentParser parser = XContentHelper.createParser(NamedXContentRegistry.EMPTY, source)) {
            XContentUtils.verifyObject(parser);
            XContentParser.Token token;
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.PASSWORD)) {
                    if (token == XContentParser.Token.VALUE_STRING) {
                        String password = parser.text();
                        char[] passwordChars = password.toCharArray();
                        password(passwordChars);
                        Arrays.fill(passwordChars, (char) 0);
                    } else {
                        throw new ElasticsearchParseException(
                                "expected field [{}] to be of type string, but found [{}] instead", currentFieldName, token);
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.PASSWORD_HASH)) {
                    if (token == XContentParser.Token.VALUE_STRING) {
                        char[] passwordChars = parser.text().toCharArray();
                        passwordHash(passwordChars);
                    } else {
                        throw new ElasticsearchParseException(
                                "expected field [{}] to be of type string, but found [{}] instead", currentFieldName, token);
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.ROLES)) {
                    if (token == XContentParser.Token.VALUE_STRING) {
                        roles(Strings.commaDelimitedListToStringArray(parser.text()));
                    } else {
                        roles(XContentUtils.readStringArray(parser, false));
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.FULL_NAME)) {
                    if (token == XContentParser.Token.VALUE_STRING) {
                        fullName(parser.text());
                    } else if (token != XContentParser.Token.VALUE_NULL) {
                        throw new ElasticsearchParseException(
                                "expected field [{}] to be of type string, but found [{}] instead", currentFieldName, token);
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.EMAIL)) {
                    if (token == XContentParser.Token.VALUE_STRING) {
                        email(parser.text());
                    } else if (token != XContentParser.Token.VALUE_NULL) {
                        throw new ElasticsearchParseException(
                                "expected field [{}] to be of type string, but found [{}] instead", currentFieldName, token);
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.METADATA)) {
                    if (token == XContentParser.Token.START_OBJECT) {
                        metadata(parser.map());
                    } else {
                        throw new ElasticsearchParseException(
                                "expected field [{}] to be of type object, but found [{}] instead", currentFieldName, token);
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.ENABLED)) {
                    if (token == XContentParser.Token.VALUE_BOOLEAN) {
                        enabled(parser.booleanValue());
                    } else {
                        throw new ElasticsearchParseException(
                                "expected field [{}] to be of type boolean, but found [{}] instead", currentFieldName, token);
                    }
                } else if (ParseFieldMatcher.STRICT.match(currentFieldName, User.Fields.USERNAME)) {
                    if (token == Token.VALUE_STRING) {
                        if (username.equals(parser.text()) == false) {
                            throw new IllegalArgumentException("[username] in source does not match the username provided [" +
                                    username + "]");
                        }
                    } else {
                        throw new ElasticsearchParseException(
                                "expected field [{}] to be of type string, but found [{}] instead", currentFieldName, token);
                    }
                } else {
                    throw new ElasticsearchParseException("failed to parse add user request. unexpected field [{}]", currentFieldName);
                }
            }
            return this;
        }
    }

}
