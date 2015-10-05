/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest.action.cat;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.count.CountRequest;
import org.elasticsearch.action.count.CountResponse;
import org.elasticsearch.action.support.QuerySourceBuilder;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.Table;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.indices.query.IndicesQueriesRegistry;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestResponse;
import org.elasticsearch.rest.action.support.RestActions;
import org.elasticsearch.rest.action.support.RestResponseListener;
import org.elasticsearch.rest.action.support.RestTable;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.rest.RestRequest.Method.GET;

public class RestCountAction extends AbstractCatAction {

    private final IndicesQueriesRegistry indicesQueriesRegistry;

    @Inject
    public RestCountAction(Settings settings, RestController restController, RestController controller, Client client, IndicesQueriesRegistry indicesQueriesRegistry) {
        super(settings, controller, client);
        restController.registerHandler(GET, "/_cat/count", this);
        restController.registerHandler(GET, "/_cat/count/{index}", this);
        this.indicesQueriesRegistry = indicesQueriesRegistry;
    }

    @Override
    protected void documentation(StringBuilder sb) {
        sb.append("/_cat/count\n");
        sb.append("/_cat/count/{index}\n");
    }

    @Override
    public void doRequest(final RestRequest request, final RestChannel channel, final Client client) {
        String[] indices = Strings.splitStringByCommaToArray(request.param("index"));
        CountRequest countRequest = new CountRequest(indices);
        String source = request.param("source");
        if (source != null) {
            try (XContentParser requestParser = XContentFactory.xContent(source).createParser(source)) {
                QueryParseContext context = new QueryParseContext(indicesQueriesRegistry);
                context.reset(requestParser);
                final QueryBuilder<?> builder = context.parseInnerQueryBuilder();
                countRequest.query(builder);
            } catch (IOException e) {
                throw new ElasticsearchException("failed to parse source", e);
            }
        } else {
            QueryBuilder<?> queryBuilder = RestActions.parseQuerySource(request);
            if (queryBuilder != null) {
                QuerySourceBuilder querySourceBuilder = new QuerySourceBuilder();
                querySourceBuilder.setQuery(queryBuilder);
                countRequest.query(queryBuilder);
            }
        }
        client.count(countRequest, new RestResponseListener<CountResponse>(channel) {
            @Override
            public RestResponse buildResponse(CountResponse countResponse) throws Exception {
                return RestTable.buildResponse(buildTable(request, countResponse), channel);
            }
        });
    }

    @Override
    protected Table getTableWithHeader(final RestRequest request) {
        Table table = new Table();
        table.startHeaders();
        table.addCell("epoch", "alias:t,time;desc:seconds since 1970-01-01 00:00:00, that the count was executed");
        table.addCell("timestamp", "alias:ts,hms;desc:time that the count was executed");
        table.addCell("count", "alias:dc,docs.count,docsCount;desc:the document count");
        table.endHeaders();
        return table;
    }

    private DateTimeFormatter dateFormat = DateTimeFormat.forPattern("HH:mm:ss");

    private Table buildTable(RestRequest request, CountResponse response) {
        Table table = getTableWithHeader(request);
        long time = System.currentTimeMillis();
        table.startRow();
        table.addCell(TimeUnit.SECONDS.convert(time, TimeUnit.MILLISECONDS));
        table.addCell(dateFormat.print(time));
        table.addCell(response.getCount());
        table.endRow();

        return table;
    }
}
