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

package org.elasticsearch.action.admin.indices.validate.query;

import jsr166y.ThreadLocalRandom;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.action.ShardOperationFailedException;
import org.elasticsearch.action.TransportActions;
import org.elasticsearch.action.support.DefaultShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.BroadcastShardOperationFailedException;
import org.elasticsearch.action.support.broadcast.TransportBroadcastOperationAction;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.IndexQueryParserService;
import org.elasticsearch.index.query.QueryParsingException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static com.google.common.collect.Lists.newArrayList;

/**
 *
 */
public class TransportValidateQueryAction extends TransportBroadcastOperationAction<ValidateQueryRequest, ValidateQueryResponse, ShardValidateQueryRequest, ShardValidateQueryResponse> {

    private final IndicesService indicesService;

    @Inject
    public TransportValidateQueryAction(Settings settings, ThreadPool threadPool, ClusterService clusterService, TransportService transportService, IndicesService indicesService) {
        super(settings, threadPool, clusterService, transportService);
        this.indicesService = indicesService;
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SEARCH;
    }

    @Override
    protected String transportAction() {
        return TransportActions.Admin.Indices.VALIDATE_QUERY;
    }

    @Override
    protected ValidateQueryRequest newRequest() {
        return new ValidateQueryRequest();
    }

    @Override
    protected ShardValidateQueryRequest newShardRequest() {
        return new ShardValidateQueryRequest();
    }

    @Override
    protected ShardValidateQueryRequest newShardRequest(ShardRouting shard, ValidateQueryRequest request) {
        String[] filteringAliases = clusterService.state().metaData().filteringAliases(shard.index(), request.indices());
        return new ShardValidateQueryRequest(shard.index(), shard.id(), filteringAliases, request);
    }

    @Override
    protected ShardValidateQueryResponse newShardResponse() {
        return new ShardValidateQueryResponse();
    }

    @Override
    protected GroupShardsIterator shards(ValidateQueryRequest request, String[] concreteIndices, ClusterState clusterState) {
        // Hard-code routing to limit request to a single shard, but still, randomize it...
        Map<String, Set<String>> routingMap = clusterState.metaData().resolveSearchRouting(Integer.toString(ThreadLocalRandom.current().nextInt(1000)), request.indices());
        return clusterService.operationRouting().searchShards(clusterState, request.indices(), concreteIndices, null, routingMap, "_local");
    }

    @Override
    protected void checkBlock(ValidateQueryRequest request, String[] concreteIndices, ClusterState state) {
        for (String index : concreteIndices) {
            state.blocks().indexBlocked(ClusterBlockLevel.READ, index);
        }
    }

    @Override
    protected ValidateQueryResponse newResponse(ValidateQueryRequest request, AtomicReferenceArray shardsResponses, ClusterState clusterState) {
        int successfulShards = 0;
        int failedShards = 0;
        boolean valid = true;
        List<ShardOperationFailedException> shardFailures = null;
        for (int i = 0; i < shardsResponses.length(); i++) {
            Object shardResponse = shardsResponses.get(i);
            if (shardResponse == null) {
                failedShards++;
            } else if (shardResponse instanceof BroadcastShardOperationFailedException) {
                failedShards++;
                if (shardFailures == null) {
                    shardFailures = newArrayList();
                }
                shardFailures.add(new DefaultShardOperationFailedException((BroadcastShardOperationFailedException) shardResponse));
            } else {
                valid = valid && ((ShardValidateQueryResponse) shardResponse).valid();
                successfulShards++;
            }
        }
        return new ValidateQueryResponse(valid, shardsResponses.length(), successfulShards, failedShards, shardFailures);
    }

    @Override
    protected ShardValidateQueryResponse shardOperation(ShardValidateQueryRequest request) throws ElasticSearchException {
        IndexQueryParserService queryParserService = indicesService.indexServiceSafe(request.index()).queryParserService();
        boolean valid;
        if (request.querySourceLength() == 0) {
            valid = true;
        } else {
            try {
                queryParserService.parse(request.querySource(), request.querySourceOffset(), request.querySourceLength());
                valid = true;
            } catch (QueryParsingException e) {
                valid = false;
            } catch (AssertionError e) {
                valid = false;
            }
        }
        return new ShardValidateQueryResponse(request.index(), request.shardId(), valid);
    }
}
