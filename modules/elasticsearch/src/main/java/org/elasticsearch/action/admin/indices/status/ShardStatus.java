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

package org.elasticsearch.action.admin.indices.status;

import org.elasticsearch.action.support.broadcast.BroadcastShardOperationResponse;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.util.SizeValue;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;

import java.io.IOException;

import static org.elasticsearch.cluster.routing.ImmutableShardRouting.*;
import static org.elasticsearch.util.SizeValue.*;

/**
 * @author kimchy (Shay Banon)
 */
public class ShardStatus extends BroadcastShardOperationResponse {

    public static class Docs {
        public static final Docs UNKNOWN = new Docs();

        int numDocs = -1;
        int maxDoc = -1;
        int deletedDocs = -1;

        public int numDocs() {
            return numDocs;
        }

        public int maxDoc() {
            return maxDoc;
        }

        public int deletedDocs() {
            return deletedDocs;
        }
    }

    private ShardRouting shardRouting;

    IndexShardState state;

    SizeValue storeSize;

    SizeValue estimatedFlushableMemorySize;

    long translogId = -1;

    long translogOperations = -1;

    Docs docs = Docs.UNKNOWN;

    ShardStatus() {
    }

    ShardStatus(ShardRouting shardRouting) {
        super(shardRouting.index(), shardRouting.id());
        this.shardRouting = shardRouting;
    }

    public ShardRouting shardRouting() {
        return this.shardRouting;
    }

    public IndexShardState state() {
        return state;
    }

    public SizeValue storeSize() {
        return storeSize;
    }

    public SizeValue estimatedFlushableMemorySize() {
        return estimatedFlushableMemorySize;
    }

    public long translogId() {
        return translogId;
    }

    public long translogOperations() {
        return translogOperations;
    }

    public Docs docs() {
        return docs;
    }

    public static ShardStatus readIndexShardStatus(StreamInput in) throws IOException {
        ShardStatus shardStatus = new ShardStatus();
        shardStatus.readFrom(in);
        return shardStatus;
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        shardRouting.writeTo(out);
        out.writeByte(state.id());
        if (storeSize == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            storeSize.writeTo(out);
        }
        if (estimatedFlushableMemorySize == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            estimatedFlushableMemorySize.writeTo(out);
        }
        out.writeLong(translogId);
        out.writeLong(translogOperations);
        out.writeInt(docs.numDocs());
        out.writeInt(docs.maxDoc());
        out.writeInt(docs.deletedDocs());
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        shardRouting = readShardRoutingEntry(in);
        state = IndexShardState.fromId(in.readByte());
        if (in.readBoolean()) {
            storeSize = readSizeValue(in);
        }
        if (in.readBoolean()) {
            estimatedFlushableMemorySize = readSizeValue(in);
        }
        translogId = in.readLong();
        translogOperations = in.readLong();
        docs = new Docs();
        docs.numDocs = in.readInt();
        docs.maxDoc = in.readInt();
        docs.deletedDocs = in.readInt();
    }
}
