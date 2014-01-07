package org.elasticsearch.percolator;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchWrapperException;
import org.elasticsearch.index.shard.ShardId;

/**
 * Exception during percolating document(s) at runtime.
 */
public class PercolateException extends ElasticsearchException implements ElasticsearchWrapperException {

    private final ShardId shardId;

    public PercolateException(String msg, ShardId shardId) {
        super(msg);
        this.shardId = shardId;
    }

    public PercolateException(ShardId shardId, String msg, Throwable cause) {
        super(msg, cause);
        this.shardId = shardId;
    }

    public ShardId getShardId() {
        return shardId;
    }
}
