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

package org.elasticsearch.action.admin.cluster.snapshots.create;

import org.elasticsearch.action.admin.cluster.ClusterAction;
import org.elasticsearch.client.ClusterAdminClient;

/**
 * Create snapshot action
 */
public class CreateSnapshotAction extends ClusterAction<CreateSnapshotRequest, CreateSnapshotResponse, CreateSnapshotRequestBuilder> {

    public static final CreateSnapshotAction INSTANCE = new CreateSnapshotAction();
    public static final String NAME = "cluster/snapshot/create";

    private CreateSnapshotAction() {
        super(NAME);
    }

    @Override
    public CreateSnapshotResponse newResponse() {
        return new CreateSnapshotResponse();
    }

    @Override
    public CreateSnapshotRequestBuilder newRequestBuilder(ClusterAdminClient client) {
        return new CreateSnapshotRequestBuilder(client);
    }
}

