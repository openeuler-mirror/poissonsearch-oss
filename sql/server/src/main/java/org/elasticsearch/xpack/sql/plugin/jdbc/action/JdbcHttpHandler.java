/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plugin.jdbc.action;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.xpack.sql.jdbc.net.protocol.Proto;
import org.elasticsearch.xpack.sql.plugin.AbstractSqlServer;
import org.elasticsearch.xpack.sql.protocol.shared.AbstractProto;
import org.elasticsearch.xpack.sql.util.StringUtils;

import java.io.DataInputStream;
import java.io.IOException;

import static org.elasticsearch.action.ActionListener.wrap;
import static org.elasticsearch.rest.BytesRestResponse.TEXT_CONTENT_TYPE;
import static org.elasticsearch.rest.RestRequest.Method.POST;
import static org.elasticsearch.rest.RestStatus.BAD_REQUEST;
import static org.elasticsearch.rest.RestStatus.INTERNAL_SERVER_ERROR;
import static org.elasticsearch.rest.RestStatus.OK;

public class JdbcHttpHandler extends BaseRestHandler { // NOCOMMIT these are call RestJdbcAction even if it isn't REST.

    public JdbcHttpHandler(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(POST, "/_jdbc", this);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        if (!request.hasContent()) {
            return badProto(StringUtils.EMPTY);
        }

        try (DataInputStream in = new DataInputStream(request.content().streamInput())) {
            try {
                return c -> client.executeLocally(JdbcAction.INSTANCE, new JdbcRequest(Proto.INSTANCE.readRequest(in)),
                        wrap(response -> c.sendResponse(new BytesRestResponse(OK, TEXT_CONTENT_TYPE, response.bytesReference())),
                                ex -> error(c, ex)));

            } catch (Exception ex) {
                return badProto("Unknown message");
            }
        }
    }

    private static RestChannelConsumer badProto(String message) {
        return c -> c.sendResponse(new BytesRestResponse(BAD_REQUEST, TEXT_CONTENT_TYPE, message));
    }

    private void error(RestChannel channel, Exception ex) {
        logger.debug("failed to parse sql request", ex);
        BytesRestResponse response = null;
        try {
            response = new BytesRestResponse(channel, ex);
        } catch (IOException e) {
            response = new BytesRestResponse(OK, TEXT_CONTENT_TYPE, ExceptionsHelper.stackTrace(e));
        }
        channel.sendResponse(response);
    }

    @Override
    public String getName() {
        return "sql_jdbc_action";
    }
}