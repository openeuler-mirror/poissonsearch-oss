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

package org.elasticsearch.cluster.routing;

import org.elasticsearch.index.shard.ShardId;

import java.util.List;

/**
 * @author kimchy (shay.banon)
 */
public class PlainShardIterator extends PlainShardsIterator implements ShardIterator {

    private final ShardId shardId;

    public PlainShardIterator(ShardId shardId, List<ShardRouting> shards) {
        super(shards);
        this.shardId = shardId;
    }

    public PlainShardIterator(ShardId shardId, List<ShardRouting> shards, int index) {
        super(shards, index);
        this.shardId = shardId;
    }

    @Override public ShardIterator reset() {
        super.reset();
        return this;
    }

    @Override public ShardId shardId() {
        return this.shardId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;

        ShardIterator that = (ShardIterator) o;

        if (shardId != null ? !shardId.equals(that.shardId()) : that.shardId() != null) return false;

        return true;
    }

    @Override public int hashCode() {
        return shardId != null ? shardId.hashCode() : 0;
    }
}
