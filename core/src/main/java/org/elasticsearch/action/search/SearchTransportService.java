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

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionListenerResponseHandler;
import org.elasticsearch.action.IndicesRequest;
import org.elasticsearch.action.OriginalIndices;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsResponse;
import org.elasticsearch.action.support.IndicesOptions;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.search.dfs.DfsSearchResult;
import org.elasticsearch.search.fetch.FetchSearchResult;
import org.elasticsearch.search.fetch.QueryFetchSearchResult;
import org.elasticsearch.search.fetch.ScrollQueryFetchSearchResult;
import org.elasticsearch.search.fetch.ShardFetchRequest;
import org.elasticsearch.search.fetch.ShardFetchSearchRequest;
import org.elasticsearch.search.internal.InternalScrollSearchRequest;
import org.elasticsearch.search.internal.ShardSearchTransportRequest;
import org.elasticsearch.search.query.QuerySearchRequest;
import org.elasticsearch.search.query.QuerySearchResult;
import org.elasticsearch.search.query.QuerySearchResultProvider;
import org.elasticsearch.search.query.ScrollQuerySearchResult;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportActionProxy;
import org.elasticsearch.transport.TaskAwareTransportRequestHandler;
import org.elasticsearch.transport.TransportChannel;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An encapsulation of {@link org.elasticsearch.search.SearchService} operations exposed through
 * transport.
 */
public class SearchTransportService extends AbstractComponent {

    public static final String FREE_CONTEXT_SCROLL_ACTION_NAME = "indices:data/read/search[free_context/scroll]";
    public static final String FREE_CONTEXT_ACTION_NAME = "indices:data/read/search[free_context]";
    public static final String CLEAR_SCROLL_CONTEXTS_ACTION_NAME = "indices:data/read/search[clear_scroll_contexts]";
    public static final String DFS_ACTION_NAME = "indices:data/read/search[phase/dfs]";
    public static final String QUERY_ACTION_NAME = "indices:data/read/search[phase/query]";
    public static final String QUERY_ID_ACTION_NAME = "indices:data/read/search[phase/query/id]";
    public static final String QUERY_SCROLL_ACTION_NAME = "indices:data/read/search[phase/query/scroll]";
    public static final String QUERY_FETCH_ACTION_NAME = "indices:data/read/search[phase/query+fetch]";
    public static final String QUERY_QUERY_FETCH_ACTION_NAME = "indices:data/read/search[phase/query/query+fetch]";
    public static final String QUERY_FETCH_SCROLL_ACTION_NAME = "indices:data/read/search[phase/query+fetch/scroll]";
    public static final String FETCH_ID_SCROLL_ACTION_NAME = "indices:data/read/search[phase/fetch/id/scroll]";
    public static final String FETCH_ID_ACTION_NAME = "indices:data/read/search[phase/fetch/id]";

    //TODO what should the setting name be?
    // TODO this should be an affix settings?
    public static final Setting<Settings> REMOTE_CLUSTERS_SEEDS = Setting.groupSetting("action.search.remote.",
            SearchTransportService::validateRemoteClustersSeeds,
            Setting.Property.NodeScope,
            Setting.Property.Dynamic);

    /**
     * The maximum number of connections that will be established to a remote cluster. For instance if there is only a single
     * seed node, other nodes will be discovered up to the given number of nodes in this setting. The default is 3.
     */
    public static final Setting<Integer> NUM_REMOTE_CONNECTIONS = Setting.intSetting("action.search.num_remote_connections",
        3, 1, Setting.Property.NodeScope);

    private final TransportService transportService;
    private volatile Map<String, RemoteClusterConnection> remoteClusters = Collections.emptyMap();

    public SearchTransportService(Settings settings, ClusterSettings clusterSettings, TransportService transportService) {
        super(settings);
        this.transportService = transportService;
        clusterSettings.addSettingsUpdateConsumer(REMOTE_CLUSTERS_SEEDS, this::setRemoteClusters,
            SearchTransportService::validateRemoteClustersSeeds);
    }

    public void setupRemoteClusters() {
        setRemoteClusters(REMOTE_CLUSTERS_SEEDS.get(settings));
    }

    private static void validateRemoteClustersSeeds(Settings settings) {
        //TODO do we need a static whitelist like in reindex from remote?
        for (String clusterName : settings.names()) {
            String[] remoteHosts = settings.getAsArray(clusterName);
            if (remoteHosts.length == 0) {
                throw new IllegalArgumentException("no hosts set for remote cluster [" + clusterName + "], at least one host is required");
            }
            for (String remoteHost : remoteHosts) {
                int portSeparator = remoteHost.lastIndexOf(':'); // in case we have a IPv6 address ie. [::1]:9300
                if (portSeparator == -1 || portSeparator == remoteHost.length()) {
                    throw new IllegalArgumentException("remote hosts need to be configured as [host:port], found [" + remoteHost + "] " +
                        "instead for remote cluster [" + clusterName + "]");
                }
                String host = remoteHost.substring(0, portSeparator);
                try {
                    InetAddress.getByName(host);
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("unknown host [" + host + "]", e);
                }
                String port = remoteHost.substring(portSeparator + 1);
                try {
                    Integer portValue = Integer.valueOf(port);
                    if (portValue <= 0) {
                        throw new IllegalArgumentException("port number must be > 0 but was: [" + portValue + "]");
                    }
                } catch(NumberFormatException e) {
                    throw new IllegalArgumentException("port must be a number, found [" + port + "] instead for remote cluster [" +
                            clusterName + "]");
                }
            }
        }
    }

    static Map<String, List<DiscoveryNode>> buildRemoteClustersSeeds(Settings settings) {
        Map<String, List<DiscoveryNode>> remoteClustersNodes = new HashMap<>();
        for (String clusterName : settings.names()) {
            String[] remoteHosts = settings.getAsArray(clusterName);
            for (String remoteHost : remoteHosts) {
                int portSeparator = remoteHost.lastIndexOf(':'); // in case we have a IPv6 address ie. [::1]:9300
                String host = remoteHost.substring(0, portSeparator);
                InetAddress hostAddress;
                try {
                    hostAddress = InetAddress.getByName(host);
                } catch (UnknownHostException e) {
                    throw new IllegalArgumentException("unknown host [" + host + "]", e);
                }
                int port = Integer.valueOf(remoteHost.substring(portSeparator + 1));
                DiscoveryNode node = new DiscoveryNode(clusterName + "#" + remoteHost,
                        new TransportAddress(new InetSocketAddress(hostAddress, port)),
                        Version.CURRENT.minimumCompatibilityVersion());
                //don't connect yet as that would require the remote node to be up and would fail the local node startup otherwise
                List<DiscoveryNode> nodes = remoteClustersNodes.get(clusterName);
                if (nodes == null) {
                    nodes = new ArrayList<>();
                    remoteClustersNodes.put(clusterName, nodes);
                }
                nodes.add(node);
            }
        }
        return remoteClustersNodes;
    }

    private void setRemoteClusters(Settings settings) {
        Map<String, List<DiscoveryNode>> seeds = buildRemoteClustersSeeds(settings);
        Map<String, RemoteClusterConnection> remoteClusters = new HashMap<>();
        for (Map.Entry<String, List<DiscoveryNode>> entry : seeds.entrySet()) {
            RemoteClusterConnection remote = this.remoteClusters.get(entry.getKey());
            if (remote == null) {
                remote = new RemoteClusterConnection(settings, entry.getKey(), entry.getValue(), transportService, 3,
                    (node) -> Version.CURRENT.isCompatible(node.getVersion()));
                remoteClusters.put(entry.getKey(), remote);
            }
            remote.updateSeedNodes(entry.getValue(), ActionListener.wrap((x) -> {},
                e -> logger.error("failed to update seed list for cluster: " + entry.getKey(), e) ));
        }
        if (remoteClusters.isEmpty() == false) {
            remoteClusters.putAll(this.remoteClusters);
            this.remoteClusters = Collections.unmodifiableMap(remoteClusters);
        }
    }

    boolean isCrossClusterSearchEnabled() {
        return remoteClusters.isEmpty() == false;
    }

    boolean isRemoteClusterRegistered(String clusterName) {
        return remoteClusters.containsKey(clusterName);
    }

    void sendSearchShards(SearchRequest searchRequest, Map<String, List<String>> remoteIndicesByCluster,
                          ActionListener<Map<String, ClusterSearchShardsResponse>> listener) {
        final CountDown responsesCountDown = new CountDown(remoteIndicesByCluster.size());
        final Map<String, ClusterSearchShardsResponse> searchShardsResponses = new ConcurrentHashMap<>();
        final AtomicReference<TransportException> transportException = new AtomicReference<>();
        for (Map.Entry<String, List<String>> entry : remoteIndicesByCluster.entrySet()) {
            final String clusterName = entry.getKey();
            RemoteClusterConnection remoteClusterConnection = remoteClusters.get(clusterName);
            if (remoteClusterConnection == null) {
                throw new IllegalArgumentException("no such remote cluster: " + clusterName);
            }
            final List<String> indices = entry.getValue();
            remoteClusterConnection.fetchSearchShards(searchRequest, indices,
                    new ActionListener<ClusterSearchShardsResponse>() {
                        @Override
                        public void onResponse(ClusterSearchShardsResponse clusterSearchShardsResponse) {
                            searchShardsResponses.put(clusterName, clusterSearchShardsResponse);
                            if (responsesCountDown.countDown()) {
                                TransportException exception = transportException.get();
                                if (exception == null) {
                                    listener.onResponse(searchShardsResponses);
                                } else {
                                    listener.onFailure(transportException.get());
                                }
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            TransportException exception = new TransportException("unable to communicate with remote cluster [" +
                                    clusterName + "]", e);
                            if (transportException.compareAndSet(null, exception) == false) {
                                exception = transportException.accumulateAndGet(exception, (previous, current) -> {
                                    current.addSuppressed(previous);
                                    return current;
                                });
                            }
                            if (responsesCountDown.countDown()) {
                                listener.onFailure(exception);
                            }
                        }
                    });
        }
    }

    public void sendFreeContext(Transport.Connection connection, final long contextId, SearchRequest request) {
        transportService.sendRequest(connection, FREE_CONTEXT_ACTION_NAME, new SearchFreeContextRequest(request, contextId),
            TransportRequestOptions.EMPTY, new ActionListenerResponseHandler<>(new ActionListener<SearchFreeContextResponse>() {
                @Override
                public void onResponse(SearchFreeContextResponse response) {
                    // no need to respond if it was freed or not
                }

                @Override
                public void onFailure(Exception e) {

                }
            }, SearchFreeContextResponse::new));
    }

    public void sendFreeContext(DiscoveryNode node, long contextId, final ActionListener<SearchFreeContextResponse> listener) {
        transportService.sendRequest(node, FREE_CONTEXT_SCROLL_ACTION_NAME, new ScrollFreeContextRequest(contextId),
            new ActionListenerResponseHandler<>(listener, SearchFreeContextResponse::new));
    }

    public void sendClearAllScrollContexts(DiscoveryNode node, final ActionListener<TransportResponse> listener) {
        transportService.sendRequest(node, CLEAR_SCROLL_CONTEXTS_ACTION_NAME, TransportRequest.Empty.INSTANCE,
            new ActionListenerResponseHandler<>(listener, () -> TransportResponse.Empty.INSTANCE));
    }

    public void sendExecuteDfs(Transport.Connection connection, final ShardSearchTransportRequest request, SearchTask task,
                               final ActionListener<DfsSearchResult> listener) {
        transportService.sendChildRequest(connection, DFS_ACTION_NAME, request, task,
            new ActionListenerResponseHandler<>(listener, DfsSearchResult::new));
    }

    public void sendExecuteQuery(Transport.Connection connection, final ShardSearchTransportRequest request, SearchTask task,
                                 final ActionListener<QuerySearchResultProvider> listener) {
        transportService.sendChildRequest(connection, QUERY_ACTION_NAME, request, task,
            new ActionListenerResponseHandler<>(listener, QuerySearchResult::new));
    }

    public void sendExecuteQuery(Transport.Connection connection, final QuerySearchRequest request, SearchTask task,
                                 final ActionListener<QuerySearchResult> listener) {
        transportService.sendChildRequest(connection, QUERY_ID_ACTION_NAME, request, task,
            new ActionListenerResponseHandler<>(listener, QuerySearchResult::new));
    }

    public void sendExecuteQuery(DiscoveryNode node, final InternalScrollSearchRequest request, SearchTask task,
                                 final ActionListener<ScrollQuerySearchResult> listener) {
        transportService.sendChildRequest(transportService.getConnection(node), QUERY_SCROLL_ACTION_NAME, request, task,
            new ActionListenerResponseHandler<>(listener, ScrollQuerySearchResult::new));
    }

    public void sendExecuteFetch(Transport.Connection connection, final ShardSearchTransportRequest request, SearchTask task,
                                 final ActionListener<QueryFetchSearchResult> listener) {
        transportService.sendChildRequest(connection, QUERY_FETCH_ACTION_NAME, request, task,
            new ActionListenerResponseHandler<>(listener, QueryFetchSearchResult::new));
    }

    public void sendExecuteFetch(Transport.Connection connection, final QuerySearchRequest request, SearchTask task,
                                 final ActionListener<QueryFetchSearchResult> listener) {
        transportService.sendChildRequest(connection, QUERY_QUERY_FETCH_ACTION_NAME, request, task,
            new ActionListenerResponseHandler<>(listener, QueryFetchSearchResult::new));
    }

    public void sendExecuteFetch(DiscoveryNode node, final InternalScrollSearchRequest request, SearchTask task,
                                 final ActionListener<ScrollQueryFetchSearchResult> listener) {
        transportService.sendChildRequest(transportService.getConnection(node), QUERY_FETCH_SCROLL_ACTION_NAME, request, task,
            new ActionListenerResponseHandler<>(listener, ScrollQueryFetchSearchResult::new));
    }

    public void sendExecuteFetch(Transport.Connection connection, final ShardFetchSearchRequest request, SearchTask task,
                                 final ActionListener<FetchSearchResult> listener) {
        sendExecuteFetch(connection, FETCH_ID_ACTION_NAME, request, task, listener);
    }

    public void sendExecuteFetchScroll(DiscoveryNode node, final ShardFetchRequest request, SearchTask task,
                                       final ActionListener<FetchSearchResult> listener) {
        sendExecuteFetch(transportService.getConnection(node), FETCH_ID_SCROLL_ACTION_NAME, request, task, listener);
    }

    private void sendExecuteFetch(Transport.Connection connection, String action, final ShardFetchRequest request, SearchTask task,
                                  final ActionListener<FetchSearchResult> listener) {
        transportService.sendChildRequest(connection, action, request, task,
            new ActionListenerResponseHandler<>(listener, FetchSearchResult::new));
    }

    static class ScrollFreeContextRequest extends TransportRequest {
        private long id;

        ScrollFreeContextRequest() {
        }

        ScrollFreeContextRequest(long id) {
            this.id = id;
        }

        public long id() {
            return this.id;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            id = in.readLong();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeLong(id);
        }
    }

    static class SearchFreeContextRequest extends ScrollFreeContextRequest implements IndicesRequest {
        private OriginalIndices originalIndices;

        public SearchFreeContextRequest() {
        }

        SearchFreeContextRequest(SearchRequest request, long id) {
            super(id);
            this.originalIndices = new OriginalIndices(request);
        }

        @Override
        public String[] indices() {
            if (originalIndices == null) {
                return null;
            }
            return originalIndices.indices();
        }

        @Override
        public IndicesOptions indicesOptions() {
            if (originalIndices == null) {
                return null;
            }
            return originalIndices.indicesOptions();
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            originalIndices = OriginalIndices.readOriginalIndices(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            OriginalIndices.writeOriginalIndices(originalIndices, out);
        }
    }

    public static class SearchFreeContextResponse extends TransportResponse {

        private boolean freed;

        SearchFreeContextResponse() {
        }

        SearchFreeContextResponse(boolean freed) {
            this.freed = freed;
        }

        public boolean isFreed() {
            return freed;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            freed = in.readBoolean();
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeBoolean(freed);
        }
    }

    public static void registerRequestHandler(TransportService transportService, SearchService searchService) {
        transportService.registerRequestHandler(FREE_CONTEXT_SCROLL_ACTION_NAME, ScrollFreeContextRequest::new, ThreadPool.Names.SAME,
            new TaskAwareTransportRequestHandler<ScrollFreeContextRequest>() {
                @Override
                public void messageReceived(ScrollFreeContextRequest request, TransportChannel channel, Task task) throws Exception {
                    boolean freed = searchService.freeContext(request.id());
                    channel.sendResponse(new SearchFreeContextResponse(freed));
                }
            });
        TransportActionProxy.registerProxyAction(transportService, FREE_CONTEXT_SCROLL_ACTION_NAME, SearchFreeContextResponse::new);
        transportService.registerRequestHandler(FREE_CONTEXT_ACTION_NAME, SearchFreeContextRequest::new, ThreadPool.Names.SAME,
            new TaskAwareTransportRequestHandler<SearchFreeContextRequest>() {
                @Override
                public void messageReceived(SearchFreeContextRequest request, TransportChannel channel, Task task) throws Exception {
                    boolean freed = searchService.freeContext(request.id());
                    channel.sendResponse(new SearchFreeContextResponse(freed));
                }
            });
        TransportActionProxy.registerProxyAction(transportService, FREE_CONTEXT_ACTION_NAME, SearchFreeContextResponse::new);
        transportService.registerRequestHandler(CLEAR_SCROLL_CONTEXTS_ACTION_NAME, () -> TransportRequest.Empty.INSTANCE,
            ThreadPool.Names.SAME,
            new TaskAwareTransportRequestHandler<TransportRequest.Empty>() {
                @Override
                public void messageReceived(TransportRequest.Empty request, TransportChannel channel, Task task) throws Exception {
                    searchService.freeAllScrollContexts();
                    channel.sendResponse(TransportResponse.Empty.INSTANCE);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, CLEAR_SCROLL_CONTEXTS_ACTION_NAME, () -> TransportResponse.Empty.INSTANCE);

        transportService.registerRequestHandler(DFS_ACTION_NAME, ShardSearchTransportRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<ShardSearchTransportRequest>() {
                @Override
                public void messageReceived(ShardSearchTransportRequest request, TransportChannel channel, Task task) throws Exception {
                    DfsSearchResult result = searchService.executeDfsPhase(request, (SearchTask)task);
                    channel.sendResponse(result);

                }
            });
        TransportActionProxy.registerProxyAction(transportService, DFS_ACTION_NAME, DfsSearchResult::new);

        transportService.registerRequestHandler(QUERY_ACTION_NAME, ShardSearchTransportRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<ShardSearchTransportRequest>() {
                @Override
                public void messageReceived(ShardSearchTransportRequest request, TransportChannel channel, Task task) throws Exception {
                    QuerySearchResultProvider result = searchService.executeQueryPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, QUERY_ACTION_NAME, QuerySearchResult::new);

        transportService.registerRequestHandler(QUERY_ID_ACTION_NAME, QuerySearchRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<QuerySearchRequest>() {
                @Override
                public void messageReceived(QuerySearchRequest request, TransportChannel channel, Task task) throws Exception {
                    QuerySearchResult result = searchService.executeQueryPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, QUERY_ID_ACTION_NAME, QuerySearchResult::new);

        transportService.registerRequestHandler(QUERY_SCROLL_ACTION_NAME, InternalScrollSearchRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<InternalScrollSearchRequest>() {
                @Override
                public void messageReceived(InternalScrollSearchRequest request, TransportChannel channel, Task task) throws Exception {
                    ScrollQuerySearchResult result = searchService.executeQueryPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, QUERY_SCROLL_ACTION_NAME, ScrollQuerySearchResult::new);

        transportService.registerRequestHandler(QUERY_FETCH_ACTION_NAME, ShardSearchTransportRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<ShardSearchTransportRequest>() {
                @Override
                public void messageReceived(ShardSearchTransportRequest request, TransportChannel channel, Task task) throws Exception {
                    QueryFetchSearchResult result = searchService.executeFetchPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, QUERY_FETCH_ACTION_NAME, QueryFetchSearchResult::new);

        transportService.registerRequestHandler(QUERY_QUERY_FETCH_ACTION_NAME, QuerySearchRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<QuerySearchRequest>() {
                @Override
                public void messageReceived(QuerySearchRequest request, TransportChannel channel, Task task) throws Exception {
                    QueryFetchSearchResult result = searchService.executeFetchPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, QUERY_QUERY_FETCH_ACTION_NAME, QueryFetchSearchResult::new);

        transportService.registerRequestHandler(QUERY_FETCH_SCROLL_ACTION_NAME, InternalScrollSearchRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<InternalScrollSearchRequest>() {
                @Override
                public void messageReceived(InternalScrollSearchRequest request, TransportChannel channel, Task task) throws Exception {
                    ScrollQueryFetchSearchResult result = searchService.executeFetchPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, QUERY_FETCH_SCROLL_ACTION_NAME, ScrollQueryFetchSearchResult::new);

        transportService.registerRequestHandler(FETCH_ID_SCROLL_ACTION_NAME, ShardFetchRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<ShardFetchRequest>() {
                @Override
                public void messageReceived(ShardFetchRequest request, TransportChannel channel, Task task) throws Exception {
                    FetchSearchResult result = searchService.executeFetchPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, FETCH_ID_SCROLL_ACTION_NAME, FetchSearchResult::new);

        transportService.registerRequestHandler(FETCH_ID_ACTION_NAME, ShardFetchSearchRequest::new, ThreadPool.Names.SEARCH,
            new TaskAwareTransportRequestHandler<ShardFetchSearchRequest>() {
                @Override
                public void messageReceived(ShardFetchSearchRequest request, TransportChannel channel, Task task) throws Exception {
                    FetchSearchResult result = searchService.executeFetchPhase(request, (SearchTask)task);
                    channel.sendResponse(result);
                }
            });
        TransportActionProxy.registerProxyAction(transportService, FETCH_ID_ACTION_NAME, FetchSearchResult::new);
    }

    Transport.Connection getConnection(DiscoveryNode node) {
        return transportService.getConnection(node);
    }

    Transport.Connection getRemoteConnection(DiscoveryNode node, String cluster) {
        RemoteClusterConnection connection = remoteClusters.get(cluster);
        if (connection == null) {
            throw new IllegalArgumentException("no such remote cluster: " + cluster);
        }
        return connection.getProxyConnection(node);
    }
}
