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

package org.elasticsearch.action.search;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsGroup;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.GroupShardsIterator;
import org.elasticsearch.cluster.routing.PlainShardIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Setting.Property;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.elasticsearch.action.search.SearchType.QUERY_AND_FETCH;
import static org.elasticsearch.action.search.SearchType.QUERY_THEN_FETCH;

public class TransportSearchAction extends HandledTransportAction<SearchRequest, SearchResponse> {

    /** The maximum number of shards for a single search request. */
    public static final Setting<Long> SHARD_COUNT_LIMIT_SETTING = Setting.longSetting(
            "action.search.shard_count.limit", 1000L, 1L, Property.Dynamic, Property.NodeScope);

    private static final char REMOTE_CLUSTER_INDEX_SEPARATOR = '|';

    private final ClusterService clusterService;
    private final SearchTransportService searchTransportService;
    private final SearchPhaseController searchPhaseController;
    private final SearchService searchService;

    @Inject
    public TransportSearchAction(Settings settings, ThreadPool threadPool, TransportService transportService, SearchService searchService,
                                 SearchTransportService searchTransportService, SearchPhaseController searchPhaseController,
                                 ClusterService clusterService, ActionFilters actionFilters,
                                 IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, SearchAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver, SearchRequest::new);
        this.searchPhaseController = searchPhaseController;
        this.searchTransportService = searchTransportService;
        SearchTransportService.registerRequestHandler(transportService, searchService);
        this.clusterService = clusterService;
        this.searchService = searchService;
    }

    private Map<String, AliasFilter> buildPerIndexAliasFilter(SearchRequest request, ClusterState clusterState,
                                                              Index[] concreteIndices, Map<String, AliasFilter> remoteAliasMap) {
        final Map<String, AliasFilter> aliasFilterMap = new HashMap<>();
        for (Index index : concreteIndices) {
            clusterState.blocks().indexBlockedRaiseException(ClusterBlockLevel.READ, index.getName());
            AliasFilter aliasFilter = searchService.buildAliasFilter(clusterState, index.getName(), request.indices());
            assert aliasFilter != null;
            aliasFilterMap.put(index.getUUID(), aliasFilter);
        }
        aliasFilterMap.putAll(remoteAliasMap);
        return aliasFilterMap;
    }

    private Map<String, Float> resolveIndexBoosts(SearchRequest searchRequest, ClusterState clusterState) {
        if (searchRequest.source() == null) {
            return Collections.emptyMap();
        }

        SearchSourceBuilder source = searchRequest.source();
        if (source.indexBoosts() == null) {
            return Collections.emptyMap();
        }

        Map<String, Float> concreteIndexBoosts = new HashMap<>();
        for (SearchSourceBuilder.IndexBoost ib : source.indexBoosts()) {
            Index[] concreteIndices =
                indexNameExpressionResolver.concreteIndices(clusterState, searchRequest.indicesOptions(), ib.getIndex());

            for (Index concreteIndex : concreteIndices) {
                concreteIndexBoosts.putIfAbsent(concreteIndex.getUUID(), ib.getBoost());
            }
        }

        return Collections.unmodifiableMap(concreteIndexBoosts);
    }

    @Override
    protected void doExecute(Task task, SearchRequest searchRequest, ActionListener<SearchResponse> listener) {
        // pure paranoia if time goes backwards we are at least positive
        final long startTimeInMillis = Math.max(0, System.currentTimeMillis());

        final String[] localIndices;
        final Map<String, List<String>> remoteIndicesByCluster = new HashMap<>();
        if (searchTransportService.isCrossClusterSearchEnabled()) {
            List<String> localIndicesList = new ArrayList<>();
            for (String index : searchRequest.indices()) {
                int i = index.indexOf(REMOTE_CLUSTER_INDEX_SEPARATOR);
                if (i >= 0) {
                    String remoteCluster = index.substring(0, i);
                    if (searchTransportService.isRemoteClusterRegistered(remoteCluster)) {
                        String remoteIndex = index.substring(i + 1);
                        List<String> indices = remoteIndicesByCluster.get(remoteCluster);
                        if (indices == null) {
                            indices = new ArrayList<>();
                            remoteIndicesByCluster.put(remoteCluster, indices);
                        }
                        indices.add(remoteIndex);
                    } else {
                        localIndicesList.add(index);
                    }
                } else {
                    localIndicesList.add(index);
                }
            }
            localIndices = localIndicesList.toArray(new String[localIndicesList.size()]);
        } else {
            localIndices = searchRequest.indices();
        }

        if (remoteIndicesByCluster.isEmpty()) {
            executeSearch((SearchTask)task, startTimeInMillis, searchRequest, localIndices, Collections.emptyList(),
                    Collections.emptySet(), Collections.emptyMap(), listener);
        } else {
            // nocommit we have to extract this logic to add unittests ideally with manually prepared searchShardsResponses etc.
            searchTransportService.sendSearchShards(searchRequest, remoteIndicesByCluster,
                ActionListener.wrap((searchShardsResponses) -> {
                    List<ShardIterator> remoteShardIterators = new ArrayList<>();
                    Set<DiscoveryNode> remoteNodes = new HashSet<>();
                    Map<String, AliasFilter> remoteAliasFilters = new HashMap<>();
                    processRemoteShards(searchShardsResponses, remoteShardIterators, remoteNodes, remoteAliasFilters);
                    executeSearch((SearchTask)task, startTimeInMillis, searchRequest, localIndices, remoteShardIterators,
                            remoteNodes, remoteAliasFilters, listener);
                }, listener::onFailure));
        }
    }

    private void processRemoteShards(Map<String, ClusterSearchShardsResponse> searchShardsResponses,
                                     List<ShardIterator> remoteShardIterators, Set<DiscoveryNode> remoteNodes,
                                     Map<String, AliasFilter> aliasFilterMap) {
        for (Map.Entry<String, ClusterSearchShardsResponse> entry : searchShardsResponses.entrySet()) {
            String clusterName = entry.getKey();
            ClusterSearchShardsResponse searchShardsResponse = entry.getValue();
            Collections.addAll(remoteNodes, searchShardsResponse.getNodes());
            Map<String, AliasFilter> indicesAndFilters = searchShardsResponse.getIndicesAndFilters();
            for (ClusterSearchShardsGroup clusterSearchShardsGroup : searchShardsResponse.getGroups()) {
                //add the cluster name to the remote index names for indices disambiguation
                //this ends up in the hits returned with the search response
                ShardId shardId = clusterSearchShardsGroup.getShardId();
                Index index = new Index(clusterName + REMOTE_CLUSTER_INDEX_SEPARATOR + shardId.getIndex().getName(),
                        shardId.getIndex().getUUID());
                ShardIterator shardIterator = new PlainShardIterator(new ShardId(index, shardId.getId()),
                        Arrays.asList(clusterSearchShardsGroup.getShards()));
                remoteShardIterators.add(shardIterator);
                AliasFilter aliasFilter;
                if (indicesAndFilters == null) {
                    //TODO this section is returned only by 5.1+ nodes. With 5.0.x nodes we should rather retrieve the alias filters
                    //using another api. What we do now causes the remote alias filters to be ignored whenever the node that we
                    //called search shards against was on 5.0.x.
                    aliasFilter = new AliasFilter(null, Strings.EMPTY_ARRAY);
                } else {
                    aliasFilter = indicesAndFilters.get(shardId.getIndexName());
                    assert aliasFilter != null;
                }
                // here we have to map the filters to the UUID since from now on we use the uuid for the lookup
                aliasFilterMap.put(shardId.getIndex().getUUID(), aliasFilter);
            }
        }
    }

    private void executeSearch(SearchTask task, long startTimeInMillis, SearchRequest searchRequest, String[] localIndices,
                               List<ShardIterator> remoteShardIterators, Set<DiscoveryNode> remoteNodes,
                               Map<String, AliasFilter> remoteAliasMap, ActionListener<SearchResponse> listener) {

        ClusterState clusterState = clusterService.state();
        clusterState.blocks().globalBlockedRaiseException(ClusterBlockLevel.READ);
        // TODO: I think startTime() should become part of ActionRequest and that should be used both for index name
        // date math expressions and $now in scripts. This way all apis will deal with now in the same way instead
        // of just for the _search api
        final Index[] indices;
        if (localIndices.length == 0 && remoteShardIterators.size() > 0) {
            indices = new Index[0]; // don't search on _all if only remote indices were specified
        } else {
            indices = indexNameExpressionResolver.concreteIndices(clusterState, searchRequest.indicesOptions(),
                startTimeInMillis, localIndices);
        }
        Map<String, AliasFilter> aliasFilter = buildPerIndexAliasFilter(searchRequest, clusterState, indices, remoteAliasMap);
        Map<String, Set<String>> routingMap = indexNameExpressionResolver.resolveSearchRouting(clusterState, searchRequest.routing(),
            searchRequest.indices());
        String[] concreteIndices = new String[indices.length];
        for (int i = 0; i < indices.length; i++) {
            concreteIndices[i] = indices[i].getName();
        }
        GroupShardsIterator localShardsIterator = clusterService.operationRouting().searchShards(clusterState, concreteIndices, routingMap,
            searchRequest.preference());
        GroupShardsIterator shardIterators = mergeShardsIterators(localShardsIterator, remoteShardIterators);

        failIfOverShardCountLimit(clusterService, shardIterators.size());

        Map<String, Float> concreteIndexBoosts = resolveIndexBoosts(searchRequest, clusterState);

        // optimize search type for cases where there is only one shard group to search on
        if (shardIterators.size() == 1) {
            // if we only have one group, then we always want Q_A_F, no need for DFS, and no need to do THEN since we hit one shard
            searchRequest.searchType(QUERY_AND_FETCH);
        }
        if (searchRequest.isSuggestOnly()) {
            // disable request cache if we have only suggest
            searchRequest.requestCache(false);
            switch (searchRequest.searchType()) {
                case DFS_QUERY_AND_FETCH:
                case DFS_QUERY_THEN_FETCH:
                    // convert to Q_T_F if we have only suggest
                    searchRequest.searchType(QUERY_THEN_FETCH);
                    break;
            }
        }

        Function<String, DiscoveryNode> nodesLookup = mergeNodesLookup(clusterState.nodes(), remoteNodes);
        searchAsyncAction(task, searchRequest, shardIterators, startTimeInMillis, nodesLookup,clusterState.version(),
            Collections.unmodifiableMap(aliasFilter), concreteIndexBoosts, listener).start();
    }

    private static GroupShardsIterator mergeShardsIterators(GroupShardsIterator localShardsIterator,
                                                            List<ShardIterator> remoteShardIterators) {
        if (remoteShardIterators.isEmpty()) {
            return localShardsIterator;
        }
        List<ShardIterator> shards = new ArrayList<>();
        for (ShardIterator shardIterator : remoteShardIterators) {
            shards.add(shardIterator);
        }
        for (ShardIterator shardIterator : localShardsIterator) {
            shards.add(shardIterator);
        }
        return new GroupShardsIterator(shards);
    }

    private Function<String, DiscoveryNode> mergeNodesLookup(DiscoveryNodes nodes, Set<DiscoveryNode> remoteNodes) {
        if (remoteNodes.isEmpty()) {
            return nodes::get;
        }
        ImmutableOpenMap.Builder<String, DiscoveryNode> builder = ImmutableOpenMap.builder(nodes.getNodes());
        for (DiscoveryNode remoteNode : remoteNodes) {
            //TODO shall we catch connect exceptions here? Otherwise we will return an error but we could rather return partial results?
            searchTransportService.connectToRemoteNode(remoteNode);
            builder.put(remoteNode.getId(), remoteNode);
        }
        return builder.build()::get;
    }

    @Override
    protected final void doExecute(SearchRequest searchRequest, ActionListener<SearchResponse> listener) {
        throw new UnsupportedOperationException("the task parameter is required");
    }

    private AbstractSearchAsyncAction searchAsyncAction(SearchTask task, SearchRequest searchRequest, GroupShardsIterator shardIterators,
                                                        long startTime, Function<String, DiscoveryNode> nodesLookup,
                                                        long clusterStateVersion, Map<String, AliasFilter> aliasFilter,
                                                        Map<String, Float> concreteIndexBoosts,
                                                        ActionListener<SearchResponse> listener) {
        Executor executor = threadPool.executor(ThreadPool.Names.SEARCH);
        AbstractSearchAsyncAction searchAsyncAction;
        switch(searchRequest.searchType()) {
            case DFS_QUERY_THEN_FETCH:
                searchAsyncAction = new SearchDfsQueryThenFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    aliasFilter, concreteIndexBoosts, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion, task);
                break;
            case QUERY_THEN_FETCH:
                searchAsyncAction = new SearchQueryThenFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    aliasFilter, concreteIndexBoosts, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion, task);
                break;
            case DFS_QUERY_AND_FETCH:
                searchAsyncAction = new SearchDfsQueryAndFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    aliasFilter, concreteIndexBoosts, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion, task);
                break;
            case QUERY_AND_FETCH:
                searchAsyncAction = new SearchQueryAndFetchAsyncAction(logger, searchTransportService, nodesLookup,
                    aliasFilter, concreteIndexBoosts, searchPhaseController, executor, searchRequest, listener, shardIterators, startTime,
                    clusterStateVersion, task);
                break;
            default:
                throw new IllegalStateException("Unknown search type: [" + searchRequest.searchType() + "]");
        }
        return searchAsyncAction;
    }

    private static void failIfOverShardCountLimit(ClusterService clusterService, int shardCount) {
        final long shardCountLimit = clusterService.getClusterSettings().get(SHARD_COUNT_LIMIT_SETTING);
        if (shardCount > shardCountLimit) {
            throw new IllegalArgumentException("Trying to query " + shardCount + " shards, which is over the limit of "
                + shardCountLimit + ". This limit exists because querying many shards at the same time can make the "
                + "job of the coordinating node very CPU and/or memory intensive. It is usually a better idea to "
                + "have a smaller number of larger shards. Update [" + SHARD_COUNT_LIMIT_SETTING.getKey()
                + "] to a greater value if you really want to query that many shards at the same time.");
        }
    }
}
