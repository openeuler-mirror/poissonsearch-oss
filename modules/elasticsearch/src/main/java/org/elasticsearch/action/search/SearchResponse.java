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

package org.elasticsearch.action.search;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.search.facets.Facets;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.util.json.JsonBuilder;
import org.elasticsearch.util.json.ToJson;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import static org.elasticsearch.action.search.ShardSearchFailure.*;
import static org.elasticsearch.search.internal.InternalSearchResponse.*;

/**
 * @author kimchy (Shay Banon)
 */
public class SearchResponse implements ActionResponse, ToJson {

    private InternalSearchResponse internalResponse;

    private String scrollId;

    private int totalShards;

    private int successfulShards;

    private ShardSearchFailure[] shardFailures;

    public SearchResponse() {
    }

    public SearchResponse(InternalSearchResponse internalResponse, String scrollId, int totalShards, int successfulShards, ShardSearchFailure[] shardFailures) {
        this.internalResponse = internalResponse;
        this.scrollId = scrollId;
        this.totalShards = totalShards;
        this.successfulShards = successfulShards;
        this.shardFailures = shardFailures;
    }

    public SearchHits hits() {
        return internalResponse.hits();
    }

    public Facets facets() {
        return internalResponse.facets();
    }

    public int totalShards() {
        return totalShards;
    }

    public int successfulShards() {
        return successfulShards;
    }

    public int failedShards() {
        return totalShards - successfulShards;
    }

    public ShardSearchFailure[] shardFailures() {
        return this.shardFailures;
    }

    public String scrollId() {
        return scrollId;
    }

    public static SearchResponse readSearchResponse(DataInput in) throws IOException, ClassNotFoundException {
        SearchResponse response = new SearchResponse();
        response.readFrom(in);
        return response;
    }

    @Override public void toJson(JsonBuilder builder, Params params) throws IOException {
        if (scrollId != null) {
            builder.field("_scrollId", scrollId);
        }
        builder.startObject("_shards");
        builder.field("total", totalShards());
        builder.field("successful", successfulShards());
        builder.field("failed", failedShards());

        if (shardFailures.length > 0) {
            builder.startArray("failures");
            for (ShardSearchFailure shardFailure : shardFailures) {
                builder.startObject();
                if (shardFailure.shard() != null) {
                    builder.field("index", shardFailure.shard().index());
                    builder.field("shardId", shardFailure.shard().shardId());
                }
                builder.field("reason", shardFailure.reason());
                builder.endObject();
            }
            builder.endArray();
        }

        builder.endObject();
        internalResponse.toJson(builder, params);
    }

    @Override public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
        internalResponse = readInternalSearchResponse(in);
        totalShards = in.readInt();
        successfulShards = in.readInt();
        int size = in.readInt();
        if (size == 0) {
            shardFailures = ShardSearchFailure.EMPTY_ARRAY;
        } else {
            shardFailures = new ShardSearchFailure[size];
            for (int i = 0; i < shardFailures.length; i++) {
                shardFailures[i] = readShardSearchFailure(in);
            }
        }
        if (in.readBoolean()) {
            scrollId = in.readUTF();
        }
    }

    @Override public void writeTo(DataOutput out) throws IOException {
        internalResponse.writeTo(out);
        out.writeInt(totalShards);
        out.writeInt(successfulShards);

        out.writeInt(shardFailures.length);
        for (ShardSearchFailure shardSearchFailure : shardFailures) {
            shardSearchFailure.writeTo(out);
        }

        if (scrollId == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(scrollId);
        }
    }
}
