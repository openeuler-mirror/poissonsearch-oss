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

package org.elasticsearch.node.service;

import java.net.InetAddress;

import com.google.common.collect.ImmutableMap;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.network.NetworkUtils;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.http.HttpServer;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.monitor.MonitorService;
import org.elasticsearch.transport.TransportService;

/**
 */
public class NodeService extends AbstractComponent {

    private final MonitorService monitorService;

    private final ClusterService clusterService;

    private final TransportService transportService;

    private final IndicesService indicesService;

    @Nullable
    private HttpServer httpServer;

    private volatile ImmutableMap<String, String> serviceAttributes = ImmutableMap.of();

    @Nullable
    private String hostname;

    @Inject
    public NodeService(Settings settings, MonitorService monitorService, Discovery discovery, ClusterService clusterService, TransportService transportService, IndicesService indicesService) {
        super(settings);
        this.monitorService = monitorService;
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.indicesService = indicesService;
        discovery.setNodeService(this);
        InetAddress address = NetworkUtils.getLocalAddress();
        if (address != null) {
            this.hostname = address.getHostName();
        }
    }

    public void setHttpServer(@Nullable HttpServer httpServer) {
        this.httpServer = httpServer;
    }

    @Deprecated
    public void putNodeAttribute(String key, String value) {
        putAttribute(key, value);
    }

    @Deprecated
    public void removeNodeAttribute(String key) {
        removeAttribute(key);
    }

    public synchronized void putAttribute(String key, String value) {
        serviceAttributes = new MapBuilder<String, String>().putAll(serviceAttributes).put(key, value).immutableMap();
    }

    public synchronized void removeAttribute(String key) {
        serviceAttributes = new MapBuilder<String, String>().putAll(serviceAttributes).remove(key).immutableMap();
    }

    /**
     * Attributes different services in the node can add to be reported as part of the node info (for example).
     */
    public ImmutableMap<String, String> attributes() {
        return this.serviceAttributes;
    }

    public NodeInfo info() {
        return new NodeInfo(hostname, clusterService.state().nodes().localNode(), serviceAttributes, settings,
                monitorService.osService().info(), monitorService.processService().info(),
                monitorService.jvmService().info(), monitorService.networkService().info(),
                transportService.info(), httpServer == null ? null : httpServer.info());
    }

    public NodeStats stats() {
        // for indices stats we want to include previous allocated shards stats as well (it will
        // only be applied to the sensible ones to use, like refresh/merge/flush/indexing stats)
        return new NodeStats(clusterService.state().nodes().localNode(), indicesService.stats(true),
                monitorService.osService().stats(), monitorService.processService().stats(),
                monitorService.jvmService().stats(), monitorService.networkService().stats(),
                transportService.stats(), httpServer == null ? null : httpServer.stats());
    }
}
