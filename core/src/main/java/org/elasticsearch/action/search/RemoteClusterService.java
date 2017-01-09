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

import org.apache.logging.log4j.util.Supplier;
import org.apache.lucene.util.IOUtils;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsGroup;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.PlainShardIterator;
import org.elasticsearch.cluster.routing.ShardIterator;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.search.internal.AliasFilter;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Basic service for accessing remote clusters via gateway nodes
 */
public final class RemoteClusterService extends AbstractComponent implements Closeable {

    /**
     * A list of initial seed nodes to discover eligible nodes from the remote cluster
     */
    //TODO this should be an affix settings?
    public static final Setting<Settings> REMOTE_CLUSTERS_SEEDS = Setting.groupSetting("search.remote.seeds.",
            RemoteClusterService::validateRemoteClustersSeeds,
            Setting.Property.NodeScope,
            Setting.Property.Dynamic);
    /**
     * The maximum number of connections that will be established to a remote cluster. For instance if there is only a single
     * seed node, other nodes will be discovered up to the given number of nodes in this setting. The default is 3.
     */
    public static final Setting<Integer> REMOTE_CONNECTIONS_PER_CLUSTER = Setting.intSetting("search.remote.connections_per_cluster",
        3, 1, Setting.Property.NodeScope);

    /**
     * The initial connect timeout for remote cluster connections
     */
    public static final Setting<TimeValue> REMOTE_INITIAL_CONNECTION_TIMEOUT_SETTING =
        Setting.positiveTimeSetting("search.remote.initial_connect_timeout", TimeValue.timeValueSeconds(30), Setting.Property.NodeScope);

    /**
     * The name of a node attribute to select nodes that should be connected to in the remote cluster.
     * For instance a node can be configured with <tt>node.node_attr.gateway: true</tt> in order to be eligible as a gateway node between
     * clusters. In that case <tt>search.remote.node_attribute: gateway</tt> can be used to filter out other nodes in the remote cluster.
     * The value of the setting is expected to be a boolean, <tt>true</tt> for nodes that can become gateways, <tt>false</tt> otherwise.
     */
    public static final Setting<String> REMOTE_NODE_ATTRIBUTE = Setting.simpleString("search.remote.node_attribute",
        Setting.Property.NodeScope);

    private static final char REMOTE_CLUSTER_INDEX_SEPARATOR = ':';

    private final TransportService transportService;
    private final int numRemoteConnections;
    private volatile Map<String, RemoteClusterConnection> remoteClusters = Collections.emptyMap();

    RemoteClusterService(Settings settings, TransportService transportService) {
        super(settings);
        this.transportService = transportService;
        numRemoteConnections = REMOTE_CONNECTIONS_PER_CLUSTER.get(settings);
    }

    /**
     * This method updates the list of remote clusters. It's intended to be used as an update consumer on the settings infrastructure
     * @param seedSettings the group settings returned from {@link #REMOTE_CLUSTERS_SEEDS}
     * @param connectionListener a listener invoked once every configured cluster has been connected to
     */
    void updateRemoteClusters(Settings seedSettings, ActionListener<Void> connectionListener) {
        Map<String, RemoteClusterConnection> remoteClusters = new HashMap<>();
        Map<String, List<DiscoveryNode>> seeds = buildRemoteClustersSeeds(seedSettings);
        if (seeds.isEmpty()) {
            connectionListener.onResponse(null);
        } else {
            CountDown countDown = new CountDown(seeds.size());
            Predicate<DiscoveryNode> nodePredicate = (node) -> Version.CURRENT.isCompatible(node.getVersion());
            if (REMOTE_NODE_ATTRIBUTE.exists(settings)) {
                // nodes can be tagged with node.attr.remote_gateway: true to allow a node to be a gateway node for
                // cross cluster search
                String attribute = REMOTE_NODE_ATTRIBUTE.get(settings);
                nodePredicate = nodePredicate.and((node) -> Boolean.getBoolean(node.getAttributes().getOrDefault(attribute, "false")));
            }
            for (Map.Entry<String, List<DiscoveryNode>> entry : seeds.entrySet()) {
                RemoteClusterConnection remote = this.remoteClusters.get(entry.getKey());
                if (remote == null) {
                    remote = new RemoteClusterConnection(settings, entry.getKey(), entry.getValue(), transportService, numRemoteConnections,
                        nodePredicate);
                    remoteClusters.put(entry.getKey(), remote);
                }
                remote.updateSeedNodes(entry.getValue(), ActionListener.wrap(
                    response -> {
                        if (countDown.countDown()) {
                            connectionListener.onResponse(response);
                        }
                    },
                    exception -> {
                        if (countDown.fastForward()) {
                            connectionListener.onFailure(exception);
                        }
                        logger.error("failed to update seed list for cluster: " + entry.getKey(), exception);
                    }));
            }
        }
        if (remoteClusters.isEmpty() == false) {
            remoteClusters.putAll(this.remoteClusters);
            this.remoteClusters = Collections.unmodifiableMap(remoteClusters);
        }
    }

    /**
     * Returns <code>true</code> if at least one remote cluster is configured
     */
    boolean isCrossClusterSearchEnabled() {
        return remoteClusters.isEmpty() == false;
    }

    /**
     * Filters out indices that refer to a remote cluster and adds them to the given per cluster indices map.
     *
     * @param perClusterIndices a map to fill with remote cluster indices from the given request indices
     * @param requestIndices the indices in the search request to filter
     * @return all indices in the requestIndices array that are not remote cluster indices
     */
    public String[] filterIndices(Map<String, List<String>> perClusterIndices, String[] requestIndices) {
        List<String> localIndicesList = new ArrayList<>();
        for (String index : requestIndices) {
            int i = index.indexOf(REMOTE_CLUSTER_INDEX_SEPARATOR);
            if (i >= 0) {
                String remoteCluster = index.substring(0, i);
                if (isRemoteClusterRegistered(remoteCluster)) {
                    String remoteIndex = index.substring(i + 1);
                    List<String> indices = perClusterIndices.get(remoteCluster);
                    if (indices == null) {
                        indices = new ArrayList<>();
                        perClusterIndices.put(remoteCluster, indices);
                    }
                    indices.add(remoteIndex);
                } else {
                    localIndicesList.add(index);
                }
            } else {
                localIndicesList.add(index);
            }
        }
        return localIndicesList.toArray(new String[localIndicesList.size()]);
}

    /**
     * Returns <code>true</code> iff the given cluster is configured as a remote cluster. Otherwise <code>false</code>
     */
    boolean isRemoteClusterRegistered(String clusterName) {
        return remoteClusters.containsKey(clusterName);
    }

    void collectSearchShards(SearchRequest searchRequest, Map<String, List<String>> remoteIndicesByCluster,
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


    Function<String, Transport.Connection> processRemoteShards(Map<String, ClusterSearchShardsResponse> searchShardsResponses,
                                                                       List<ShardIterator> remoteShardIterators,
                                                                       Map<String, AliasFilter> aliasFilterMap) {
        Map<String, Supplier<Transport.Connection>> nodeToCluster = new HashMap<>();
        for (Map.Entry<String, ClusterSearchShardsResponse> entry : searchShardsResponses.entrySet()) {
            String clusterName = entry.getKey();
            ClusterSearchShardsResponse searchShardsResponse = entry.getValue();
            for (DiscoveryNode remoteNode : searchShardsResponse.getNodes()) {
                nodeToCluster.put(remoteNode.getId(), () -> getConnection(remoteNode, clusterName));
            }
            Map<String, AliasFilter> indicesAndFilters = searchShardsResponse.getIndicesAndFilters();
            for (ClusterSearchShardsGroup clusterSearchShardsGroup : searchShardsResponse.getGroups()) {
                //add the cluster name to the remote index names for indices disambiguation
                //this ends up in the hits returned with the search response
                ShardId shardId = clusterSearchShardsGroup.getShardId();
                Index remoteIndex = shardId.getIndex();
                Index index = new Index(clusterName + REMOTE_CLUSTER_INDEX_SEPARATOR + remoteIndex.getName(), remoteIndex.getUUID());
                ShardIterator shardIterator = new PlainShardIterator(new ShardId(index, shardId.getId()),
                    Arrays.asList(clusterSearchShardsGroup.getShards()));
                remoteShardIterators.add(shardIterator);
                AliasFilter aliasFilter;
                if (indicesAndFilters == null) {
                    aliasFilter = new AliasFilter(null, Strings.EMPTY_ARRAY);
                } else {
                    aliasFilter = indicesAndFilters.get(shardId.getIndexName());
                    assert aliasFilter != null;
                }
                // here we have to map the filters to the UUID since from now on we use the uuid for the lookup
                aliasFilterMap.put(remoteIndex.getUUID(), aliasFilter);
            }
        }
        return (nodeId) -> {
            Supplier<Transport.Connection> supplier = nodeToCluster.get(nodeId);
            if (supplier == null) {
                throw new IllegalArgumentException("unknown remote node: " + nodeId);
            }
            return supplier.get();
        };
    }

    /**
     * Returns a connection to the given node on the given remote cluster
     * @throws IllegalArgumentException if the remote cluster is unknown
     */
    private Transport.Connection getConnection(DiscoveryNode node, String cluster) {
        RemoteClusterConnection connection = remoteClusters.get(cluster);
        if (connection == null) {
            throw new IllegalArgumentException("no such remote cluster: " + cluster);
        }
        return connection.getConnection(node);
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

    static void validateRemoteClustersSeeds(Settings settings) {
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
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("port must be a number, found [" + port + "] instead for remote cluster [" +
                        clusterName + "]");
                }
            }
        }
    }

    /**
     * Connects to all remote clusters in a blocking fashion. This should be called on node startup to establish an initial connection
     * to all configured seed nodes.
     */
    void initializeRemoteClusters() {
        final TimeValue timeValue = REMOTE_INITIAL_CONNECTION_TIMEOUT_SETTING.get(settings);
        final PlainActionFuture<Void> future = new PlainActionFuture<>();
        updateRemoteClusters(REMOTE_CLUSTERS_SEEDS.get(settings), future);
        try {
            future.get(timeValue.millis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (TimeoutException ex) {
            logger.warn("failed to connect to remote clusters within {}", timeValue.toString());
        } catch (Exception e) {
            throw new IllegalStateException("failed to connect to remote clusters", e);
        }
    }

    @Override
    public void close() throws IOException {
        IOUtils.close(remoteClusters.values());
    }
}
