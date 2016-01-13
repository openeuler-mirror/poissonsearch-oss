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

package org.elasticsearch.plugin.reindex;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.support.replication.ReplicationRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.search.builder.SearchSourceBuilder;

import java.io.IOException;
import java.util.Arrays;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public abstract class AbstractBulkByScrollRequest<Self extends AbstractBulkByScrollRequest<Self>>
        extends ActionRequest<Self> {
    public static final int SIZE_ALL_MATCHES = -1;
    private static final TimeValue DEFAULT_SCROLL_TIMEOUT = TimeValue.timeValueMinutes(5);
    private static final int DEFAULT_SCROLL_SIZE = 100;

    /**
     * The search to be executed.
     */
    private SearchRequest source;

    /**
     * Maximum number of processed documents. Defaults to -1 meaning process all
     * documents.
     */
    private int size = SIZE_ALL_MATCHES;

    /**
     * Should version conflicts cause aborts? Defaults to true.
     */
    private boolean abortOnVersionConflict = true;

    /**
     * Call refresh on the indexes we've written to after the request ends?
     */
    private boolean refresh = false;

    /**
     * Timeout to wait for the shards on to be available for each bulk request?
     */
    private TimeValue timeout = ReplicationRequest.DEFAULT_TIMEOUT;

    /**
     * Consistency level for write requests.
     */
    private WriteConsistencyLevel consistency = WriteConsistencyLevel.DEFAULT;

    public AbstractBulkByScrollRequest() {
    }

    public AbstractBulkByScrollRequest(SearchRequest source) {
        this.source = source;

        // Set the defaults which differ from SearchRequest's defaults.
        source.scroll(DEFAULT_SCROLL_TIMEOUT);
        source.source(new SearchSourceBuilder());
        source.source().version(true);
        source.source().size(DEFAULT_SCROLL_SIZE);
    }

    /**
     * `this` cast to Self. Used for building fluent methods without cast
     * warnings.
     */
    protected abstract Self self();

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException e = source.validate();
        if (source.source().from() != -1) {
            e = addValidationError("from is not supported in this context", e);
        }
        if (false == (size == -1 || size > 0)) {
            e = addValidationError(
                    "size should be greater than 0 if the request is limited to some number of documents or -1 if it isn't but it was ["
                            + size + "]",
                    e);
        }
        return e;
    }

    /**
     * Maximum number of processed documents. Defaults to -1 meaning process all
     * documents.
     */
    public int getSize() {
        return size;
    }

    /**
     * Maximum number of processed documents. Defaults to -1 meaning process all
     * documents.
     */
    public Self setSize(int size) {
        this.size = size;
        return self();
    }

    /**
     * Should version conflicts cause aborts? Defaults to false.
     */
    public boolean isAbortOnVersionConflict() {
        return abortOnVersionConflict;
    }

    /**
     * Should version conflicts cause aborts? Defaults to false.
     */
    public Self setAbortOnVersionConflict(boolean abortOnVersionConflict) {
        this.abortOnVersionConflict = abortOnVersionConflict;
        return self();
    }

    /**
     * Sets abortOnVersionConflict based on REST-friendly names.
     */
    public void setConflicts(String conflicts) {
        switch (conflicts) {
        case "proceed":
            setAbortOnVersionConflict(false);
            return;
        case "abort":
            setAbortOnVersionConflict(true);
            return;
        default:
            throw new IllegalArgumentException("conflicts may only be \"proceed\" or \"abort\" but was [" + conflicts + "]");
        }
    }

    /**
     * The search request that matches the documents to process.
     */
    public SearchRequest getSource() {
        return source;
    }

    /**
     * Call refresh on the indexes we've written to after the request ends?
     */
    public boolean isRefresh() {
        return refresh;
    }

    /**
     * Call refresh on the indexes we've written to after the request ends?
     */
    public Self setRefresh(boolean refresh) {
        this.refresh = refresh;
        return self();
    }

    /**
     * Timeout to wait for the shards on to be available for each bulk request?
     */
    public TimeValue getTimeout() {
        return timeout;
    }

    /**
     * Timeout to wait for the shards on to be available for each bulk request?
     */
    public Self setTimeout(TimeValue timeout) {
        this.timeout = timeout;
        return self();
    }

    /**
     * Consistency level for write requests.
     */
    public WriteConsistencyLevel getConsistency() {
        return consistency;
    }

    /**
     * Consistency level for write requests.
     */
    public Self setConsistency(WriteConsistencyLevel consistency) {
        this.consistency = consistency;
        return self();
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        source = new SearchRequest();
        source.readFrom(in);
        abortOnVersionConflict = in.readBoolean();
        size = in.readVInt();
        refresh = in.readBoolean();
        timeout = TimeValue.readTimeValue(in);
        consistency = WriteConsistencyLevel.fromId(in.readByte());
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        source.writeTo(out);
        out.writeBoolean(abortOnVersionConflict);
        out.writeVInt(size);
        out.writeBoolean(refresh);
        timeout.writeTo(out);
        out.writeByte(consistency.id());
    }

    /**
     * Append a short description of the search request to a StringBuilder. Used
     * to make toString.
     */
    protected void searchToString(StringBuilder b) {
        if (source.indices() != null && source.indices().length != 0) {
            b.append(Arrays.toString(source.indices()));
        } else {
            b.append("[all indices]");
        }
        if (source.types() != null && source.types().length != 0) {
            b.append(source.types());
        }
    }

}
