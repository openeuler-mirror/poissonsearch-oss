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

package org.elasticsearch.cache;

import org.elasticsearch.cache.memory.ByteBufferCache;
import org.elasticsearch.cache.query.parser.QueryParserCache;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;

/**
 * @author kimchy (shay.banon)
 */
public class NodeCache extends AbstractComponent implements ClusterStateListener {

    private final ClusterService clusterService;

    private final ByteBufferCache byteBufferCache;

    private final QueryParserCache queryParserCache;

    @Inject public NodeCache(Settings settings, ByteBufferCache byteBufferCache, QueryParserCache queryParserCache, ClusterService clusterService) {
        super(settings);
        this.clusterService = clusterService;
        this.byteBufferCache = byteBufferCache;
        this.queryParserCache = queryParserCache;
        clusterService.add(this);
    }

    public void close() {
        clusterService.remove(this);
        byteBufferCache.close();
        queryParserCache.clear();
    }

    public ByteBufferCache byteBuffer() {
        return byteBufferCache;
    }

    public QueryParserCache queryParser() {
        return queryParserCache;
    }

    // listen on cluster change events to invalidate the query parser cache
    @Override public void clusterChanged(ClusterChangedEvent event) {
        // TODO we can do better by detecting just mappings changes
        if (event.metaDataChanged()) {
            queryParserCache.clear();
        }
    }
}
