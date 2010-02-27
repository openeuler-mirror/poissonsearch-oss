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

package org.elasticsearch.action.admin.indices.optimize;

import org.elasticsearch.action.support.broadcast.BroadcastShardOperationRequest;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * @author kimchy (shay.banon)
 */
class ShardOptimizeRequest extends BroadcastShardOperationRequest {

    private boolean waitForMerge = true;

    private int maxNumSegments = -1;

    private boolean onlyExpungeDeletes = false;

    private boolean flush = false;

    private boolean refresh = false;

    ShardOptimizeRequest() {
    }

    public ShardOptimizeRequest(String index, int shardId, OptimizeRequest request) {
        super(index, shardId);
        waitForMerge = request.waitForMerge();
        maxNumSegments = request.maxNumSegments();
        onlyExpungeDeletes = request.onlyExpungeDeletes();
        flush = request.flush();
        refresh = request.refresh();
    }

    boolean waitForMerge() {
        return waitForMerge;
    }

    int maxNumSegments() {
        return maxNumSegments;
    }

    public boolean onlyExpungeDeletes() {
        return onlyExpungeDeletes;
    }

    public boolean flush() {
        return flush;
    }

    public boolean refresh() {
        return refresh;
    }

    @Override public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
        super.readFrom(in);
        waitForMerge = in.readBoolean();
        maxNumSegments = in.readInt();
        onlyExpungeDeletes = in.readBoolean();
        flush = in.readBoolean();
        refresh = in.readBoolean();
    }

    @Override public void writeTo(DataOutput out) throws IOException {
        super.writeTo(out);
        out.writeBoolean(waitForMerge);
        out.writeInt(maxNumSegments);
        out.writeBoolean(onlyExpungeDeletes);
        out.writeBoolean(flush);
        out.writeBoolean(refresh);
    }
}