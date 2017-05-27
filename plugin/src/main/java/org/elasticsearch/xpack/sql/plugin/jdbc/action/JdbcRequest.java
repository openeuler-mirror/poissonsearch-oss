/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plugin.jdbc.action;

import java.util.Objects;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.xpack.sql.jdbc.net.protocol.Request;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class JdbcRequest extends ActionRequest {

    private Request request;

    public JdbcRequest() {}

    public JdbcRequest(Request request) {
        this.request = request;
    }

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (request == null) {
            validationException = addValidationError("no request has been specified", validationException);
        }
        return validationException;
    }

    public Request request() {
        return request;
    }

    public JdbcRequest request(Request request) {
        this.request = request;
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(request);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        JdbcRequest other = (JdbcRequest) obj;
        return Objects.equals(request, other.request);
    }

    @Override
    public String getDescription() {
        return "SQL JDBC [" + request + "]";
    }
}