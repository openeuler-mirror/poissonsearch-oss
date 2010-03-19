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

package org.elasticsearch.action.deletebyquery;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.replication.IndicesReplicationOperationRequest;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.util.Required;
import org.elasticsearch.util.Strings;
import org.elasticsearch.util.TimeValue;
import org.elasticsearch.util.Unicode;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;

import java.io.IOException;
import java.util.Arrays;

import static org.elasticsearch.action.Actions.*;

/**
 * A request to delete all documents that matching a specific query. Best created with
 * {@link org.elasticsearch.client.Requests#deleteByQueryRequest(String...)}.
 *
 * <p>The request requires the query source to be set either using {@link #querySource(org.elasticsearch.index.query.QueryBuilder)},
 * or {@link #querySource(byte[])}.
 *
 * @author kimchy (shay.banon)
 * @see DeleteByQueryResponse
 * @see org.elasticsearch.client.Requests#deleteByQueryRequest(String...)
 * @see org.elasticsearch.client.Client#deleteByQuery(DeleteByQueryRequest)
 */
public class DeleteByQueryRequest extends IndicesReplicationOperationRequest {

    private byte[] querySource;
    private String queryParserName;
    private String[] types = Strings.EMPTY_ARRAY;

    /**
     * Constructs a new delete by query request to run against the provided indices. No indices means
     * it will run against all indices.
     */
    public DeleteByQueryRequest(String... indices) {
        this.indices = indices;
    }

    DeleteByQueryRequest() {
    }

    /**
     * Should the listener be called on a separate thread if needed.
     */
    @Override public DeleteByQueryRequest listenerThreaded(boolean threadedListener) {
        super.listenerThreaded(threadedListener);
        return this;
    }

    @Override public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = super.validate();
        if (querySource == null) {
            validationException = addValidationError("query is missing", validationException);
        }
        return validationException;
    }

    /**
     * The query source to execute.
     */
    byte[] querySource() {
        return querySource;
    }

    /**
     * The query source to execute.
     *
     * @see org.elasticsearch.index.query.json.JsonQueryBuilders
     */
    @Required public DeleteByQueryRequest querySource(QueryBuilder queryBuilder) {
        return querySource(queryBuilder.buildAsBytes());
    }

    /**
     * The query source to execute. It is preferable to use either {@link #querySource(byte[])}
     * or {@link #querySource(org.elasticsearch.index.query.QueryBuilder)}.
     */
    @Required public DeleteByQueryRequest querySource(String querySource) {
        return querySource(Unicode.fromStringAsBytes(querySource));
    }

    /**
     * The query source to execute.
     */
    @Required public DeleteByQueryRequest querySource(byte[] querySource) {
        this.querySource = querySource;
        return this;
    }

    /**
     * The query parse name to use. If not set, will use the default one.
     */
    String queryParserName() {
        return queryParserName;
    }

    /**
     * The query parse name to use. If not set, will use the default one.
     */
    public DeleteByQueryRequest queryParserName(String queryParserName) {
        this.queryParserName = queryParserName;
        return this;
    }

    /**
     * The types of documents the query will run against. Defaults to all types.
     */
    String[] types() {
        return this.types;
    }

    /**
     * The types of documents the query will run against. Defaults to all types.
     */
    public DeleteByQueryRequest types(String... types) {
        this.types = types;
        return this;
    }

    /**
     * A timeout to wait if the delete by query operation can't be performed immediately. Defaults to <tt>1m</tt>.
     */
    public DeleteByQueryRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        return this;
    }

    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        querySource = new byte[in.readVInt()];
        in.readFully(querySource);
        if (in.readBoolean()) {
            queryParserName = in.readUTF();
        }
    }

    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(querySource.length);
        out.writeBytes(querySource);
        if (queryParserName == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(queryParserName);
        }
    }

    @Override public String toString() {
        return "[" + Arrays.toString(indices) + "][" + Arrays.toString(types) + "], querySource[" + Unicode.fromBytes(querySource) + "]";
    }
}