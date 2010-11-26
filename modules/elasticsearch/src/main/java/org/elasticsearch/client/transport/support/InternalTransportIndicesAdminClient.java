/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

package org.elasticsearch.client.transport.support;

import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest;
import org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse;
import org.elasticsearch.action.admin.indices.cache.clear.ClearIndicesCacheRequest;
import org.elasticsearch.action.admin.indices.cache.clear.ClearIndicesCacheResponse;
import org.elasticsearch.action.admin.indices.close.CloseIndexRequest;
import org.elasticsearch.action.admin.indices.close.CloseIndexResponse;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.action.admin.indices.flush.FlushRequest;
import org.elasticsearch.action.admin.indices.flush.FlushResponse;
import org.elasticsearch.action.admin.indices.gateway.snapshot.GatewaySnapshotRequest;
import org.elasticsearch.action.admin.indices.gateway.snapshot.GatewaySnapshotResponse;
import org.elasticsearch.action.admin.indices.mapping.delete.DeleteMappingRequest;
import org.elasticsearch.action.admin.indices.mapping.delete.DeleteMappingResponse;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.admin.indices.open.OpenIndexRequest;
import org.elasticsearch.action.admin.indices.open.OpenIndexResponse;
import org.elasticsearch.action.admin.indices.optimize.OptimizeRequest;
import org.elasticsearch.action.admin.indices.optimize.OptimizeResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.admin.indices.settings.UpdateSettingsRequest;
import org.elasticsearch.action.admin.indices.settings.UpdateSettingsResponse;
import org.elasticsearch.action.admin.indices.status.IndicesStatusRequest;
import org.elasticsearch.action.admin.indices.status.IndicesStatusResponse;
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateResponse;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.client.support.AbstractIndicesAdminClient;
import org.elasticsearch.client.transport.TransportClientNodesService;
import org.elasticsearch.client.transport.action.admin.indices.alias.ClientTransportIndicesAliasesAction;
import org.elasticsearch.client.transport.action.admin.indices.analyze.ClientTransportAnalyzeAction;
import org.elasticsearch.client.transport.action.admin.indices.cache.clear.ClientTransportClearIndicesCacheAction;
import org.elasticsearch.client.transport.action.admin.indices.close.ClientTransportCloseIndexAction;
import org.elasticsearch.client.transport.action.admin.indices.create.ClientTransportCreateIndexAction;
import org.elasticsearch.client.transport.action.admin.indices.delete.ClientTransportDeleteIndexAction;
import org.elasticsearch.client.transport.action.admin.indices.flush.ClientTransportFlushAction;
import org.elasticsearch.client.transport.action.admin.indices.gateway.snapshot.ClientTransportGatewaySnapshotAction;
import org.elasticsearch.client.transport.action.admin.indices.mapping.delete.ClientTransportDeleteMappingAction;
import org.elasticsearch.client.transport.action.admin.indices.mapping.put.ClientTransportPutMappingAction;
import org.elasticsearch.client.transport.action.admin.indices.open.ClientTransportOpenIndexAction;
import org.elasticsearch.client.transport.action.admin.indices.optimize.ClientTransportOptimizeAction;
import org.elasticsearch.client.transport.action.admin.indices.refresh.ClientTransportRefreshAction;
import org.elasticsearch.client.transport.action.admin.indices.settings.ClientTransportUpdateSettingsAction;
import org.elasticsearch.client.transport.action.admin.indices.status.ClientTransportIndicesStatusAction;
import org.elasticsearch.client.transport.action.admin.indices.template.delete.ClientTransportDeleteIndexTemplateAction;
import org.elasticsearch.client.transport.action.admin.indices.template.put.ClientTransportPutIndexTemplateAction;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.threadpool.ThreadPool;

/**
 * @author kimchy (shay.banon)
 */
public class InternalTransportIndicesAdminClient extends AbstractIndicesAdminClient implements IndicesAdminClient {

    private final TransportClientNodesService nodesService;

    private final ThreadPool threadPool;

    private final ClientTransportIndicesStatusAction indicesStatusAction;

    private final ClientTransportCreateIndexAction createIndexAction;

    private final ClientTransportDeleteIndexAction deleteIndexAction;

    private final ClientTransportCloseIndexAction closeIndexAction;

    private final ClientTransportOpenIndexAction openIndexAction;

    private final ClientTransportRefreshAction refreshAction;

    private final ClientTransportFlushAction flushAction;

    private final ClientTransportOptimizeAction optimizeAction;

    private final ClientTransportPutMappingAction putMappingAction;

    private final ClientTransportDeleteMappingAction deleteMappingAction;

    private final ClientTransportGatewaySnapshotAction gatewaySnapshotAction;

    private final ClientTransportIndicesAliasesAction indicesAliasesAction;

    private final ClientTransportClearIndicesCacheAction clearIndicesCacheAction;

    private final ClientTransportUpdateSettingsAction updateSettingsAction;

    private final ClientTransportAnalyzeAction analyzeAction;

    private final ClientTransportPutIndexTemplateAction putIndexTemplateAction;

    private final ClientTransportDeleteIndexTemplateAction deleteIndexTemplateAction;

    @Inject public InternalTransportIndicesAdminClient(Settings settings, TransportClientNodesService nodesService, ThreadPool threadPool,
                                                       ClientTransportIndicesStatusAction indicesStatusAction,
                                                       ClientTransportCreateIndexAction createIndexAction, ClientTransportDeleteIndexAction deleteIndexAction,
                                                       ClientTransportCloseIndexAction closeIndexAction, ClientTransportOpenIndexAction openIndexAction,
                                                       ClientTransportRefreshAction refreshAction, ClientTransportFlushAction flushAction, ClientTransportOptimizeAction optimizeAction,
                                                       ClientTransportPutMappingAction putMappingAction, ClientTransportDeleteMappingAction deleteMappingAction, ClientTransportGatewaySnapshotAction gatewaySnapshotAction,
                                                       ClientTransportIndicesAliasesAction indicesAliasesAction, ClientTransportClearIndicesCacheAction clearIndicesCacheAction,
                                                       ClientTransportUpdateSettingsAction updateSettingsAction, ClientTransportAnalyzeAction analyzeAction,
                                                       ClientTransportPutIndexTemplateAction putIndexTemplateAction, ClientTransportDeleteIndexTemplateAction deleteIndexTemplateAction) {
        this.nodesService = nodesService;
        this.threadPool = threadPool;
        this.indicesStatusAction = indicesStatusAction;
        this.createIndexAction = createIndexAction;
        this.deleteIndexAction = deleteIndexAction;
        this.closeIndexAction = closeIndexAction;
        this.openIndexAction = openIndexAction;
        this.refreshAction = refreshAction;
        this.flushAction = flushAction;
        this.optimizeAction = optimizeAction;
        this.putMappingAction = putMappingAction;
        this.deleteMappingAction = deleteMappingAction;
        this.gatewaySnapshotAction = gatewaySnapshotAction;
        this.indicesAliasesAction = indicesAliasesAction;
        this.clearIndicesCacheAction = clearIndicesCacheAction;
        this.updateSettingsAction = updateSettingsAction;
        this.analyzeAction = analyzeAction;
        this.putIndexTemplateAction = putIndexTemplateAction;
        this.deleteIndexTemplateAction = deleteIndexTemplateAction;
    }

    @Override public ThreadPool threadPool() {
        return this.threadPool;
    }

    @Override public ActionFuture<IndicesStatusResponse> status(final IndicesStatusRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<IndicesStatusResponse>>() {
            @Override public ActionFuture<IndicesStatusResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return indicesStatusAction.execute(node, request);
            }
        });
    }

    @Override public void status(final IndicesStatusRequest request, final ActionListener<IndicesStatusResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Void>() {
            @Override public Void doWithNode(DiscoveryNode node) throws ElasticSearchException {
                indicesStatusAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<CreateIndexResponse> create(final CreateIndexRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<CreateIndexResponse>>() {
            @Override public ActionFuture<CreateIndexResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return createIndexAction.execute(node, request);
            }
        });
    }

    @Override public void create(final CreateIndexRequest request, final ActionListener<CreateIndexResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                createIndexAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<DeleteIndexResponse> delete(final DeleteIndexRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<DeleteIndexResponse>>() {
            @Override public ActionFuture<DeleteIndexResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return deleteIndexAction.execute(node, request);
            }
        });
    }

    @Override public void delete(final DeleteIndexRequest request, final ActionListener<DeleteIndexResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                deleteIndexAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<CloseIndexResponse> close(final CloseIndexRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<org.elasticsearch.action.ActionFuture<CloseIndexResponse>>() {
            @Override public ActionFuture<CloseIndexResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return closeIndexAction.execute(node, request);
            }
        });
    }

    @Override public void close(final CloseIndexRequest request, final ActionListener<CloseIndexResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                closeIndexAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<OpenIndexResponse> open(final OpenIndexRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<org.elasticsearch.action.ActionFuture<OpenIndexResponse>>() {
            @Override public ActionFuture<OpenIndexResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return openIndexAction.execute(node, request);
            }
        });
    }

    @Override public void open(final OpenIndexRequest request, final ActionListener<OpenIndexResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                openIndexAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<RefreshResponse> refresh(final RefreshRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<RefreshResponse>>() {
            @Override public ActionFuture<RefreshResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return refreshAction.execute(node, request);
            }
        });
    }

    @Override public void refresh(final RefreshRequest request, final ActionListener<RefreshResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Void>() {
            @Override public Void doWithNode(DiscoveryNode node) throws ElasticSearchException {
                refreshAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<FlushResponse> flush(final FlushRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<FlushResponse>>() {
            @Override public ActionFuture<FlushResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return flushAction.execute(node, request);
            }
        });
    }

    @Override public void flush(final FlushRequest request, final ActionListener<FlushResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                flushAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<OptimizeResponse> optimize(final OptimizeRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<OptimizeResponse>>() {
            @Override public ActionFuture<OptimizeResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return optimizeAction.execute(node, request);
            }
        });
    }

    @Override public void optimize(final OptimizeRequest request, final ActionListener<OptimizeResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<Void>>() {
            @Override public ActionFuture<Void> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                optimizeAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<PutMappingResponse> putMapping(final PutMappingRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<PutMappingResponse>>() {
            @Override public ActionFuture<PutMappingResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return putMappingAction.execute(node, request);
            }
        });
    }

    @Override public void putMapping(final PutMappingRequest request, final ActionListener<PutMappingResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Void>() {
            @Override public Void doWithNode(DiscoveryNode node) throws ElasticSearchException {
                putMappingAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<DeleteMappingResponse> deleteMapping(final DeleteMappingRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<DeleteMappingResponse>>() {
            @Override public ActionFuture<DeleteMappingResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return deleteMappingAction.execute(node, request);
            }
        });
    }

    @Override public void deleteMapping(final DeleteMappingRequest request, final ActionListener<DeleteMappingResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Void>() {
            @Override public Void doWithNode(DiscoveryNode node) throws ElasticSearchException {
                deleteMappingAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<GatewaySnapshotResponse> gatewaySnapshot(final GatewaySnapshotRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<GatewaySnapshotResponse>>() {
            @Override public ActionFuture<GatewaySnapshotResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return gatewaySnapshotAction.execute(node, request);
            }
        });
    }

    @Override public void gatewaySnapshot(final GatewaySnapshotRequest request, final ActionListener<GatewaySnapshotResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                gatewaySnapshotAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<IndicesAliasesResponse> aliases(final IndicesAliasesRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<IndicesAliasesResponse>>() {
            @Override public ActionFuture<IndicesAliasesResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return indicesAliasesAction.execute(node, request);
            }
        });
    }

    @Override public void aliases(final IndicesAliasesRequest request, final ActionListener<IndicesAliasesResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Void>() {
            @Override public Void doWithNode(DiscoveryNode node) throws ElasticSearchException {
                indicesAliasesAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<ClearIndicesCacheResponse> clearCache(final ClearIndicesCacheRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<ClearIndicesCacheResponse>>() {
            @Override public ActionFuture<ClearIndicesCacheResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return clearIndicesCacheAction.execute(node, request);
            }
        });
    }

    @Override public void clearCache(final ClearIndicesCacheRequest request, final ActionListener<ClearIndicesCacheResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Void>() {
            @Override public Void doWithNode(DiscoveryNode node) throws ElasticSearchException {
                clearIndicesCacheAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<UpdateSettingsResponse> updateSettings(final UpdateSettingsRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<UpdateSettingsResponse>>() {
            @Override public ActionFuture<UpdateSettingsResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return updateSettingsAction.execute(node, request);
            }
        });
    }

    @Override public void updateSettings(final UpdateSettingsRequest request, final ActionListener<UpdateSettingsResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Void>() {
            @Override public Void doWithNode(DiscoveryNode node) throws ElasticSearchException {
                updateSettingsAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<AnalyzeResponse> analyze(final AnalyzeRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<AnalyzeResponse>>() {
            @Override public ActionFuture<AnalyzeResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return analyzeAction.execute(node, request);
            }
        });
    }

    @Override public void analyze(final AnalyzeRequest request, final ActionListener<AnalyzeResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                analyzeAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<PutIndexTemplateResponse> putTemplate(final PutIndexTemplateRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<PutIndexTemplateResponse>>() {
            @Override public ActionFuture<PutIndexTemplateResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return putIndexTemplateAction.execute(node, request);
            }
        });
    }

    @Override public void putTemplate(final PutIndexTemplateRequest request, final ActionListener<PutIndexTemplateResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                putIndexTemplateAction.execute(node, request, listener);
                return null;
            }
        });
    }

    @Override public ActionFuture<DeleteIndexTemplateResponse> deleteTemplate(final DeleteIndexTemplateRequest request) {
        return nodesService.execute(new TransportClientNodesService.NodeCallback<ActionFuture<DeleteIndexTemplateResponse>>() {
            @Override public ActionFuture<DeleteIndexTemplateResponse> doWithNode(DiscoveryNode node) throws ElasticSearchException {
                return deleteIndexTemplateAction.execute(node, request);
            }
        });
    }

    @Override public void deleteTemplate(final DeleteIndexTemplateRequest request, final ActionListener<DeleteIndexTemplateResponse> listener) {
        nodesService.execute(new TransportClientNodesService.NodeCallback<Object>() {
            @Override public Object doWithNode(DiscoveryNode node) throws ElasticSearchException {
                deleteIndexTemplateAction.execute(node, request, listener);
                return null;
            }
        });
    }
}
