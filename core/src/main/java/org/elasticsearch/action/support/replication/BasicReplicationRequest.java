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

package org.elasticsearch.action.support.replication;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.index.shard.ShardId;

/**
 * A replication request that has no more information than ReplicationRequest.
 * Unfortunately ReplicationRequest can't be declared as a type parameter
 * because it has a self referential type parameter of its own. So use this
 * instead.
 */
public class BasicReplicationRequest extends ReplicationRequest<BasicReplicationRequest> {
    public BasicReplicationRequest() {

    }

    /**
     * Creates a new request that inherits headers and context from the request
     * provided as argument.
     */
    public BasicReplicationRequest(ActionRequest<?> request) {
        super(request);
    }

    /**
     * Creates a new request with resolved shard id
     */
    public BasicReplicationRequest(ActionRequest<?> request, ShardId shardId) {
        super(request, shardId);
    }

    /**
     * Copy constructor that creates a new request that is a copy of the one
     * provided as an argument.
     */
    protected BasicReplicationRequest(BasicReplicationRequest request) {
        super(request);
    }

}
