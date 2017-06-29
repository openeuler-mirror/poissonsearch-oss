/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.upgrade.actions;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.action.support.master.MasterNodeReadOperationRequestBuilder;
import org.elasticsearch.action.support.master.MasterNodeReadRequest;
import org.elasticsearch.action.support.master.TransportMasterNodeAction;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.upgrade.IndexUpgradeService;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.action.ValidateActions.addValidationError;
import static org.elasticsearch.xpack.upgrade.IndexUpgradeService.UPGRADE_INDEX_OPTIONS;

public class IndexUpgradeAction extends Action<IndexUpgradeAction.Request, BulkByScrollResponse,
        IndexUpgradeAction.RequestBuilder> {

    public static final IndexUpgradeAction INSTANCE = new IndexUpgradeAction();
    public static final String NAME = "cluster:admin/xpack/upgrade";

    private IndexUpgradeAction() {
        super(NAME);
    }

    @Override
    public RequestBuilder newRequestBuilder(ElasticsearchClient client) {
        return new RequestBuilder(client, this);
    }

    @Override
    public BulkByScrollResponse newResponse() {
        return new BulkByScrollResponse();
    }

    public static class Request extends MasterNodeReadRequest<Request> implements IndicesRequest {

        private String index = null;
        private Map<String, String> extraParams = Collections.emptyMap();

        // for serialization
        public Request() {

        }

        public Request(String index) {
            this.index = index;
        }

        public String index() {
            return index;
        }

        /**
         * Sets the index.
         */
        @SuppressWarnings("unchecked")
        public final Request index(String index) {
            this.index = index;
            return this;
        }

        @Override
        public String[] indices() {
            return new String[]{index};
        }

        @Override
        public IndicesOptions indicesOptions() {
            return UPGRADE_INDEX_OPTIONS;
        }


        public Map<String, String> extraParams() {
            return extraParams;
        }

        public Request extraParams(Map<String, String> extraParams) {
            this.extraParams = extraParams;
            return this;
        }

        @Override
        public ActionRequestValidationException validate() {
            ActionRequestValidationException validationException = null;
            if (index == null) {
                validationException = addValidationError("index is missing", validationException);
            }
            if (extraParams == null) {
                validationException = addValidationError("params are missing", validationException);
            }
            return validationException;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            index = in.readString();
            extraParams = in.readMap(StreamInput::readString, StreamInput::readString);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeString(index);
            out.writeMap(extraParams, StreamOutput::writeString, StreamOutput::writeString);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Request request = (Request) o;
            return Objects.equals(index, request.index) &&
                    Objects.equals(extraParams, request.extraParams);
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, extraParams);
        }
    }

    public static class RequestBuilder extends MasterNodeReadOperationRequestBuilder<Request, BulkByScrollResponse, RequestBuilder> {

        protected RequestBuilder(ElasticsearchClient client, IndexUpgradeAction action) {
            super(client, action, new Request());
        }

        public RequestBuilder setIndex(String index) {
            request.index(index);
            return this;
        }

        public RequestBuilder setExtraParams(Map<String, String> params) {
            request.extraParams(params);
            return this;
        }


    }

    public static class TransportAction extends TransportMasterNodeAction<Request, BulkByScrollResponse> {

        private final IndexUpgradeService indexUpgradeService;

        @Inject
        public TransportAction(Settings settings, TransportService transportService, ClusterService clusterService,
                               ThreadPool threadPool, ActionFilters actionFilters,
                               IndexUpgradeService indexUpgradeService,
                               IndexNameExpressionResolver indexNameExpressionResolver) {
            super(settings, IndexUpgradeAction.NAME, transportService, clusterService, threadPool, actionFilters,
                    indexNameExpressionResolver, Request::new);
            this.indexUpgradeService = indexUpgradeService;
        }

        @Override
        protected String executor() {
            return ThreadPool.Names.GENERIC;
        }

        @Override
        protected BulkByScrollResponse newResponse() {
            return new BulkByScrollResponse();
        }

        @Override
        protected ClusterBlockException checkBlock(Request request, ClusterState state) {
            // Cluster is not affected but we look up repositories in metadata
            return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
        }

        @Override
        protected final void masterOperation(final Request request, ClusterState state, ActionListener<BulkByScrollResponse> listener) {
            indexUpgradeService.upgrade(request.index(), request.extraParams(), state, listener);
        }
    }
}