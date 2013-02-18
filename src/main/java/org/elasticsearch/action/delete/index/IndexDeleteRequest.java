/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.action.delete.index;

import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.support.replication.IndexReplicationOperationRequest;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

/**
 *
 */
public class IndexDeleteRequest extends IndexReplicationOperationRequest<IndexDeleteRequest> {

    private String type;
    private String id;
    private boolean refresh = false;
    private long version;

    IndexDeleteRequest() {
    }

    public IndexDeleteRequest(DeleteRequest request) {
        this.timeout = request.getTimeout();
        this.consistencyLevel = request.getConsistencyLevel();
        this.replicationType = request.getReplicationType();
        this.index = request.getIndex();
        this.type = request.getType();
        this.id = request.getId();
        this.refresh = request.isRefresh();
        this.version = request.getVersion();
    }

    public String getType() {
        return this.type;
    }

    public String getId() {
        return this.id;
    }

    public boolean isRefresh() {
        return this.refresh;
    }

    public long getVersion() {
        return this.version;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        type = in.readString();
        id = in.readString();
        refresh = in.readBoolean();
        version = in.readLong();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(type);
        out.writeString(id);
        out.writeBoolean(refresh);
        out.writeLong(version);
    }
}
