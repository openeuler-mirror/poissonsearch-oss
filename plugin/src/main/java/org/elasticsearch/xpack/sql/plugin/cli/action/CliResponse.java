/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plugin.cli.action;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.xpack.sql.cli.net.protocol.Response;
import org.elasticsearch.xpack.sql.session.RowSetCursor;

public class CliResponse extends ActionResponse {

    private Response response;
    private RowSetCursor cursor;

    public CliResponse() {}

    public CliResponse(Response response) {
        this(response, null);
    }

    public CliResponse(Response response, RowSetCursor cursor) {
        this.response = response;
        this.cursor = cursor;
    }

    public Response response() {
        return response;
    }

    public RowSetCursor cursor() {
        return cursor;
    }
}