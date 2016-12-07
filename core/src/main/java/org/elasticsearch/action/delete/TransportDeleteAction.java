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

package org.elasticsearch.action.delete;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.bulk.TransportBulkAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 * Performs the delete operation.
 */
public class TransportDeleteAction extends HandledTransportAction<DeleteRequest, DeleteResponse> {

    private final TransportBulkAction bulkAction;

    @Inject
    public TransportDeleteAction(Settings settings, TransportService transportService,
                                 ThreadPool threadPool,
                                 ActionFilters actionFilters,
                                 IndexNameExpressionResolver indexNameExpressionResolver,
                                 TransportBulkAction bulkAction) {
        super(settings, DeleteAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, DeleteRequest::new);
        this.bulkAction = bulkAction;
    }

    @Override
    protected void doExecute(Task task, final DeleteRequest request, final ActionListener<DeleteResponse> listener) {
        BulkRequest bulkRequest = new BulkRequest();
        bulkRequest.add(request);
        bulkRequest.setRefreshPolicy(request.getRefreshPolicy());
        bulkRequest.timeout(request.timeout());
        bulkRequest.waitForActiveShards(request.waitForActiveShards());
        request.setRefreshPolicy(WriteRequest.RefreshPolicy.NONE);
        bulkAction.execute(task, bulkRequest, new ActionListener<BulkResponse>() {
            @Override
            public void onResponse(BulkResponse bulkItemResponses) {
                assert bulkItemResponses.getItems().length == 1: "expected only one item in bulk request";
                BulkItemResponse bulkItemResponse = bulkItemResponses.getItems()[0];
                if (bulkItemResponse.isFailed() == false) {
                    DeleteResponse response = bulkItemResponse.getResponse();
                    listener.onResponse(response);
                } else {
                    listener.onFailure(bulkItemResponse.getFailure().getCause());
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    @Override
    protected void doExecute(DeleteRequest request, ActionListener<DeleteResponse> listener) {
        throw new UnsupportedOperationException("must have task with request");
    }
}
