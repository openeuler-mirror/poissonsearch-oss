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

package org.elasticsearch.action.index;

import org.elasticsearch.ElasticSearchGenerationException;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.replication.ShardReplicationOperationRequest;
import org.elasticsearch.util.Required;
import org.elasticsearch.util.TimeValue;
import org.elasticsearch.util.Unicode;
import org.elasticsearch.util.io.FastByteArrayOutputStream;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;
import org.elasticsearch.util.json.JsonBuilder;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.action.Actions.*;
import static org.elasticsearch.util.json.Jackson.*;

/**
 * Index request to index a typed JSON document into a specific index and make it searchable. Best
 * created using {@link org.elasticsearch.client.Requests#indexRequest(String)}.
 *
 * <p>The index requires the {@link #index()}, {@link #type(String)}, {@link #id(String)} and
 * {@link #source(byte[])} to be set.
 *
 * <p>The source (JSON to index) can be set in its bytes form using ({@link #source(byte[])}),
 * its string form ({@link #source(String)}) or using a {@link org.elasticsearch.util.json.JsonBuilder}
 * ({@link #source(org.elasticsearch.util.json.JsonBuilder)}).
 *
 * <p>If the {@link #id(String)} is not set, it will be automatically generated.
 *
 * @author kimchy (shay.banon)
 * @see IndexResponse
 * @see org.elasticsearch.client.Requests#indexRequest(String)
 * @see org.elasticsearch.client.Client#index(IndexRequest)
 */
public class IndexRequest extends ShardReplicationOperationRequest {

    /**
     * Operation type controls if the type of the index operation.
     */
    public static enum OpType {
        /**
         * Index the source. If there an existing document with the id, it will
         * be replaced.
         */
        INDEX((byte) 0),
        /**
         * Creates the resource. Simply adds it to the index, if there is an existing
         * document with the id, then it won't be removed.
         */
        CREATE((byte) 1);

        private byte id;

        OpType(byte id) {
            this.id = id;
        }

        /**
         * The internal representation of the operation type.
         */
        public byte id() {
            return id;
        }

        /**
         * Constructs the operation type from its internal representation.
         */
        public static OpType fromId(byte id) {
            if (id == 0) {
                return INDEX;
            } else if (id == 1) {
                return CREATE;
            } else {
                throw new ElasticSearchIllegalArgumentException("No type match for [" + id + "]");
            }
        }
    }

    private String type;
    private String id;
    private byte[] source;
    private OpType opType = OpType.INDEX;

    /**
     * Constructs a new index request against the specific index. The {@link #type(String)},
     * {@link #id(String)} and {@link #source(byte[])} must be set.
     */
    public IndexRequest(String index) {
        this.index = index;
    }

    /**
     * Constructs a new index request against the index, type, id and using the source.
     *
     * @param index  The index to index into
     * @param type   The type to index into
     * @param id     The id of document
     * @param source The JSON source document
     */
    public IndexRequest(String index, String type, String id, byte[] source) {
        this.index = index;
        this.type = type;
        this.id = id;
        this.source = source;
    }

    IndexRequest() {
    }

    @Override public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = super.validate();
        if (type == null) {
            validationException = addValidationError("type is missing", validationException);
        }
        if (source == null) {
            validationException = addValidationError("source is missing", validationException);
        }
        return validationException;
    }

    /**
     * Sets the index the index operation will happen on.
     */
    @Override public IndexRequest index(String index) {
        super.index(index);
        return this;
    }

    /**
     * Should the listener be called on a separate thread if needed.
     */
    @Override public IndexRequest listenerThreaded(boolean threadedListener) {
        super.listenerThreaded(threadedListener);
        return this;
    }

    /**
     * Controls if the operation will be executed on a separate thread when executed locally. Defaults
     * to <tt>true</tt> when running in embedded mode.
     */
    @Override public IndexRequest operationThreaded(boolean threadedOperation) {
        super.operationThreaded(threadedOperation);
        return this;
    }

    /**
     * The type of the indexed document.
     */
    String type() {
        return type;
    }

    /**
     * Sets the type of the indexed document.
     */
    @Required public IndexRequest type(String type) {
        this.type = type;
        return this;
    }

    /**
     * The id of the indexed document. If not set, will be automatically generated.
     */
    String id() {
        return id;
    }

    /**
     * Sets the id of the indexed document. If not set, will be automatically generated.
     */
    public IndexRequest id(String id) {
        this.id = id;
        return this;
    }

    /**
     * The source of the JSON document to index.
     */
    byte[] source() {
        return source;
    }

    /**
     * Writes the JSON as a {@link Map}.
     *
     * @param source The map to index
     */
    @Required public IndexRequest source(Map source) throws ElasticSearchGenerationException {
        FastByteArrayOutputStream os = FastByteArrayOutputStream.Cached.cached();
        try {
            defaultObjectMapper().writeValue(os, source);
        } catch (IOException e) {
            throw new ElasticSearchGenerationException("Failed to generate [" + source + "]", e);
        }
        this.source = os.copiedByteArray();
        return this;
    }

    /**
     * Sets the JSON source to index.
     *
     * <p>Note, its preferable to either set it using {@link #source(org.elasticsearch.util.json.JsonBuilder)}
     * or using the {@link #source(byte[])}.
     */
    @Required public IndexRequest source(String source) {
        this.source = Unicode.fromStringAsBytes(source);
        return this;
    }

    /**
     * Sets the JSON source to index.
     */
    @Required public IndexRequest source(JsonBuilder jsonBuilder) {
        try {
            return source(jsonBuilder.copiedBytes());
        } catch (IOException e) {
            throw new ElasticSearchIllegalArgumentException("Failed to build json for index request", e);
        }
    }

    /**
     * Sets the JSON source to index.
     */
    @Required public IndexRequest source(byte[] source) {
        this.source = source;
        return this;
    }

    /**
     * A timeout to wait if the index operation can't be performed immediately. Defaults to <tt>1m</tt>.
     */
    public IndexRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Sets the type of operation to perform.
     */
    public IndexRequest opType(OpType opType) {
        this.opType = opType;
        return this;
    }

    /**
     * The type of operation to perform.
     */
    public OpType opType() {
        return this.opType;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        type = in.readUTF();
        if (in.readBoolean()) {
            id = in.readUTF();
        }
        source = new byte[in.readVInt()];
        in.readFully(source);
        opType = OpType.fromId(in.readByte());
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeUTF(type);
        if (id == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(id);
        }
        out.writeVInt(source.length);
        out.writeBytes(source);
        out.writeByte(opType.id());
    }

    @Override public String toString() {
        return "[" + index + "][" + type + "][" + id + "], source[" + Unicode.fromBytes(source) + "]";
    }
}