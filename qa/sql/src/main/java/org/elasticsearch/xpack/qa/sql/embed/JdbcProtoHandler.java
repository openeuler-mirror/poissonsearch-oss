/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.embed;

import com.sun.net.httpserver.HttpExchange;

import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.sql.jdbc.net.protocol.Proto;
import org.elasticsearch.xpack.sql.plugin.AbstractSqlServer;
import org.elasticsearch.xpack.sql.plugin.jdbc.JdbcServer;
import org.elasticsearch.xpack.sql.protocol.shared.AbstractProto;
import org.elasticsearch.xpack.sql.protocol.shared.Request;
import org.elasticsearch.xpack.sql.protocol.shared.Response;

import java.io.DataInput;
import java.io.IOException;

import static org.elasticsearch.action.ActionListener.wrap;

class JdbcProtoHandler extends ProtoHandler<Response> {

    private final JdbcServer server;

    JdbcProtoHandler(Client client) {
        super(client, response -> AbstractSqlServer.write(AbstractProto.CURRENT_VERSION, response));
        this.server = new JdbcServer(planExecutor(client), clusterName, () -> info.getNode().getName(), info.getVersion(),
                info.getBuild());
    }

    @Override
    protected void handle(HttpExchange http, DataInput in) throws IOException {
        Request req = Proto.INSTANCE.readRequest(in);
        server.handle(req, wrap(resp -> sendHttpResponse(http, resp), ex -> fail(http, ex)));
    }
}