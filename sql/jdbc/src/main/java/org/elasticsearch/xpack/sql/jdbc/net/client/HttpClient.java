/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.jdbc.net.client;

import org.elasticsearch.xpack.sql.client.shared.ClientException;
import org.elasticsearch.xpack.sql.client.shared.JreHttpUrlConnection;
import org.elasticsearch.xpack.sql.client.shared.JreHttpUrlConnection.ResponseOrException;
import org.elasticsearch.xpack.sql.jdbc.JdbcException;
import org.elasticsearch.xpack.sql.jdbc.JdbcSQLException;
import org.elasticsearch.xpack.sql.jdbc.jdbc.JdbcConfiguration;
import org.elasticsearch.xpack.sql.jdbc.net.protocol.Proto;
import org.elasticsearch.xpack.sql.protocol.shared.Request;
import org.elasticsearch.xpack.sql.protocol.shared.Response;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.SQLException;

// http client
// handles nodes discovery, fail-over, errors, etc...
class HttpClient {

    private final JdbcConfiguration cfg;

    HttpClient(JdbcConfiguration connectionInfo) throws SQLException {
        this.cfg = connectionInfo;
    }

    void setNetworkTimeout(long millis) {
        cfg.networkTimeout(millis);
    }

    long getNetworkTimeout() {
        return cfg.networkTimeout();
    }

    boolean head() throws JdbcSQLException {
        try {
            return AccessController.doPrivileged((PrivilegedAction<Boolean>) () ->
                    JreHttpUrlConnection.http("", "error_trace", cfg, JreHttpUrlConnection::head));
        } catch (ClientException ex) {
            throw new JdbcSQLException(ex, "Cannot ping server");
        }
    }

    Response post(Request request) throws SQLException {
        try {
            return AccessController.doPrivileged((PrivilegedAction<ResponseOrException<Response>>) () ->
                JreHttpUrlConnection.http("_sql/jdbc", "error_trace", cfg, con ->
                    con.post(
                        out -> Proto.INSTANCE.writeRequest(request, out),
                        in -> Proto.INSTANCE.readResponse(request, in)
                    )
                )
            ).getResponseOrThrowException();
        } catch (ClientException ex) {
            throw new JdbcSQLException(ex, "Transport failure");
        }
    }
}
