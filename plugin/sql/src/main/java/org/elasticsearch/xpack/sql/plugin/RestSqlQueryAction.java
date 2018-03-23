/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plugin;

import org.elasticsearch.Version;
import org.elasticsearch.client.node.NodeClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.BytesRestResponse;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.rest.action.RestResponseListener;
import org.elasticsearch.rest.action.RestToXContentListener;
import org.elasticsearch.xpack.sql.session.Cursor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.elasticsearch.rest.RestRequest.Method.GET;
import static org.elasticsearch.rest.RestRequest.Method.POST;

public class RestSqlQueryAction extends BaseRestHandler {

    public RestSqlQueryAction(Settings settings, RestController controller) {
        super(settings);
        controller.registerHandler(GET, SqlQueryAction.REST_ENDPOINT, this);
        controller.registerHandler(POST, SqlQueryAction.REST_ENDPOINT, this);
    }

    @Override
    protected RestChannelConsumer prepareRequest(RestRequest request, NodeClient client) throws IOException {
        SqlQueryRequest sqlRequest;
        try (XContentParser parser = request.contentOrSourceParamParser()) {
            sqlRequest = SqlQueryRequest.fromXContent(parser, AbstractSqlRequest.Mode.fromString(request.param("mode")));
        }

        XContentType xContentType = XContentType.fromMediaTypeOrFormat(request.param("format", request.header("Accept")));
        if (xContentType != null) {
            // The client expects us to send back results in a XContent format
            return channel -> client.executeLocally(SqlQueryAction.INSTANCE, sqlRequest,
                    new RestToXContentListener<SqlQueryResponse>(channel) {
                        @Override
                        public RestResponse buildResponse(SqlQueryResponse response, XContentBuilder builder) throws Exception {
                            // Make sure we only display JDBC-related data if JDBC is enabled
                            response.toXContent(builder, request);
                            return new BytesRestResponse(getStatus(response), builder);
                        }
                    });
        }

        // The client accepts a text format
        TextFormat text = TextFormat.fromMediaTypeOrFormat(request);
        long startNanos = System.nanoTime();

        return channel -> client.execute(SqlQueryAction.INSTANCE, sqlRequest, new RestResponseListener<SqlQueryResponse>(channel) {
            @Override
            public RestResponse buildResponse(SqlQueryResponse response) throws Exception {
                Cursor cursor = Cursor.decodeFromString(sqlRequest.cursor());
                final String data = text.format(cursor, request, response);

                RestResponse restResponse = new BytesRestResponse(RestStatus.OK, text.contentType(request),
                        data.getBytes(StandardCharsets.UTF_8));

                Cursor responseCursor = text.wrapCursor(cursor, response);

                if (responseCursor != Cursor.EMPTY) {
                    restResponse.addHeader("Cursor", Cursor.encodeToString(Version.CURRENT, responseCursor));
                }
                restResponse.addHeader("Took-nanos", Long.toString(System.nanoTime() - startNanos));

                return restResponse;
            }
        });
    }

    @Override
    public String getName() {
        return "xpack_sql_action";
    }
}