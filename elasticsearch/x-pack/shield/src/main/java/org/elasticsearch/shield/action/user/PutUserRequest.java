/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action.user;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.shield.authc.support.CharArrays;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.action.ValidateActions.addValidationError;

/**
 * Request object to put a native user.
 */
public class PutUserRequest extends ActionRequest<PutUserRequest> {

    private String username;
    private String[] roles;
    private String fullName;
    private String email;
    private Map<String, Object> metadata;
    private char[] passwordHash;

    public PutUserRequest() {
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (username == null) {
            validationException = addValidationError("user is missing", validationException);
        }
        if (roles == null) {
            validationException = addValidationError("roles are missing", validationException);
        }
        if (passwordHash == null) {
            validationException = addValidationError("passwordHash is missing", validationException);
        }
        return validationException;
    }

    public void username(String username) {
        this.username = username;
    }

    public void roles(String... roles) {
        this.roles = roles;
    }

    public void fullName(String fullName) {
        this.fullName = fullName;
    }

    public void email(String email) {
        this.email = email;
    }

    public void metadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public void passwordHash(char[] passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String username() {
        return username;
    }

    public String[] roles() {
        return roles;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public char[] passwordHash() {
        return passwordHash;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        username = in.readString();
        passwordHash = CharArrays.utf8BytesToChars(in.readByteArray());
        roles = in.readStringArray();
        fullName = in.readOptionalString();
        email = in.readOptionalString();
        metadata = in.readBoolean() ? in.readMap() : null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(username);
        out.writeByteArray(CharArrays.toUtf8Bytes(passwordHash));
        out.writeStringArray(roles);
        out.writeOptionalString(fullName);
        out.writeOptionalString(email);
        if (metadata == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeMap(metadata);
        }
    }
}
