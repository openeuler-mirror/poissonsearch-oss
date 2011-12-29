package org.elasticsearch.client.action.admin.indices.validate.query;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.validate.query.ValidateQueryRequest;
import org.elasticsearch.action.admin.indices.validate.query.ValidateQueryResponse;
import org.elasticsearch.action.support.broadcast.BroadcastOperationThreading;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.client.action.admin.indices.support.BaseIndicesRequestBuilder;
import org.elasticsearch.index.query.QueryBuilder;

/**
 *
 */
public class ValidateQueryRequestBuilder extends BaseIndicesRequestBuilder<ValidateQueryRequest, ValidateQueryResponse> {
    public ValidateQueryRequestBuilder(IndicesAdminClient client) {
        super(client, new ValidateQueryRequest());
    }

    /**
     * Sets the indices the query validation will run against.
     */
    public ValidateQueryRequestBuilder setIndices(String... indices) {
        request.indices(indices);
        return this;
    }

    /**
     * The types of documents the query will run against. Defaults to all types.
     */
    public ValidateQueryRequestBuilder setTypes(String... types) {
        request.types(types);
        return this;
    }

    /**
     * The query source to validate.
     *
     * @see org.elasticsearch.index.query.QueryBuilders
     */
    public ValidateQueryRequestBuilder setQuery(QueryBuilder queryBuilder) {
        request.query(queryBuilder);
        return this;
    }

    /**
     * The query source to validate.
     *
     * @see org.elasticsearch.index.query.QueryBuilders
     */
    public ValidateQueryRequestBuilder setQuery(byte[] querySource) {
        request.query(querySource);
        return this;
    }

    /**
     * Controls the operation threading model.
     */
    public ValidateQueryRequestBuilder setOperationThreading(BroadcastOperationThreading operationThreading) {
        request.operationThreading(operationThreading);
        return this;
    }

    /**
     * Should the listener be called on a separate thread if needed.
     */
    public ValidateQueryRequestBuilder setListenerThreaded(boolean threadedListener) {
        request.listenerThreaded(threadedListener);
        return this;
    }

    @Override
    protected void doExecute(ActionListener<ValidateQueryResponse> listener) {
        client.validateQuery(request, listener);
    }
}
