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

package org.elasticsearch.index.snapshots;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.index.shard.IndexShardException;
import org.elasticsearch.index.shard.ShardId;

import java.io.IOException;

/**
 * Generic shard restore exception
 */
public class IndexShardRestoreException extends IndexShardException {
    public IndexShardRestoreException(ShardId shardId, String msg) {
        super(shardId, msg);
    }

    public IndexShardRestoreException(ShardId shardId, String msg, Throwable cause) {
        super(shardId, msg, cause);
    }

    public IndexShardRestoreException(StreamInput in) throws IOException{
        super(in);
    }
}
