/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli.integration.server;

import com.sun.net.httpserver.HttpExchange;

import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.sql.TestUtils;
import org.elasticsearch.xpack.sql.cli.net.protocol.ProtoUtils;
import org.elasticsearch.xpack.sql.cli.net.protocol.Request;
import org.elasticsearch.xpack.sql.cli.net.protocol.Response;
import org.elasticsearch.xpack.sql.plugin.cli.http.CliServerProtoUtils;
import org.elasticsearch.xpack.sql.plugin.cli.server.CliServer;
import org.elasticsearch.xpack.sql.test.server.ProtoHandler;

import java.io.DataInput;
import java.io.IOException;

import static org.elasticsearch.action.ActionListener.wrap;

class CliProtoHandler extends ProtoHandler<Response> {

    private final CliServer server;
    
    CliProtoHandler(Client client) {
        super(client, ProtoUtils::readHeader, CliServerProtoUtils::write);
        this.server = new CliServer(TestUtils.planExecutor(client), clusterName, info.getNode().getName(), info.getVersion() , info.getBuild());
    }

    @Override
    protected void handle(HttpExchange http, DataInput in) throws IOException {
        Request req = ProtoUtils.readRequest(in);
        server.handle(req, wrap(resp -> sendHttpResponse(http, resp), ex -> error(http, ex)));
    }
}