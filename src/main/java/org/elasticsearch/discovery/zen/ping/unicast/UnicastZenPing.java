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

package org.elasticsearch.discovery.zen.ping.unicast;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import com.google.common.collect.Lists;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.discovery.zen.elect.ElectMasterService;
import org.elasticsearch.discovery.zen.ping.PingContextProvider;
import org.elasticsearch.discovery.zen.ping.ZenPing;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.*;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.collect.Lists.newArrayList;
import static org.elasticsearch.common.unit.TimeValue.readTimeValue;
import static org.elasticsearch.common.util.concurrent.ConcurrentCollections.newConcurrentMap;
import static org.elasticsearch.discovery.zen.ping.ZenPing.PingResponse.readPingResponse;

/**
 *
 */
public class UnicastZenPing extends AbstractLifecycleComponent<ZenPing> implements ZenPing {

    public static final String ACTION_NAME = "internal:discovery/zen/unicast";

    public static final int LIMIT_PORTS_COUNT = 1;

    private final ThreadPool threadPool;
    private final TransportService transportService;
    private final ClusterName clusterName;
    private final ElectMasterService electMasterService;

    private final int concurrentConnects;

    private final DiscoveryNode[] configuredTargetNodes;

    private volatile PingContextProvider contextProvider;

    private final AtomicInteger pingHandlerIdGenerator = new AtomicInteger();

    // used to generate unique ids for nodes/address we temporarily connect to
    private final AtomicInteger unicastNodeIdGenerator = new AtomicInteger();

    // used as a node id prefix for nodes/address we temporarily connect to
    private static final String UNICAST_NODE_PREFIX = "#zen_unicast_";

    private final Map<Integer, PingCollection> receivedResponses = newConcurrentMap();

    // a list of temporal responses a node will return for a request (holds requests from other configuredTargetNodes)
    private final Queue<PingResponse> temporalResponses = ConcurrentCollections.newQueue();

    private final CopyOnWriteArrayList<UnicastHostsProvider> hostsProviders = new CopyOnWriteArrayList<>();

    public UnicastZenPing(Settings settings, ThreadPool threadPool, TransportService transportService, ClusterName clusterName,
                          Version version, ElectMasterService electMasterService, @Nullable Set<UnicastHostsProvider> unicastHostsProviders) {
        super(settings);
        this.threadPool = threadPool;
        this.transportService = transportService;
        this.clusterName = clusterName;
        this.electMasterService = electMasterService;

        if (unicastHostsProviders != null) {
            for (UnicastHostsProvider unicastHostsProvider : unicastHostsProviders) {
                addHostsProvider(unicastHostsProvider);
            }
        }

        this.concurrentConnects = componentSettings.getAsInt("concurrent_connects", 10);
        String[] hostArr = componentSettings.getAsArray("hosts");
        // trim the hosts
        for (int i = 0; i < hostArr.length; i++) {
            hostArr[i] = hostArr[i].trim();
        }
        List<String> hosts = Lists.newArrayList(hostArr);
        logger.debug("using initial hosts {}, with concurrent_connects [{}]", hosts, concurrentConnects);

        List<DiscoveryNode> configuredTargetNodes = Lists.newArrayList();
        for (String host : hosts) {
            try {
                TransportAddress[] addresses = transportService.addressesFromString(host);
                // we only limit to 1 addresses, makes no sense to ping 100 ports
                for (int i = 0; (i < addresses.length && i < LIMIT_PORTS_COUNT); i++) {
                    configuredTargetNodes.add(new DiscoveryNode(UNICAST_NODE_PREFIX + unicastNodeIdGenerator.incrementAndGet() + "#", addresses[i], version.minimumCompatibilityVersion()));
                }
            } catch (Exception e) {
                throw new ElasticsearchIllegalArgumentException("Failed to resolve address for [" + host + "]", e);
            }
        }
        this.configuredTargetNodes = configuredTargetNodes.toArray(new DiscoveryNode[configuredTargetNodes.size()]);

        transportService.registerHandler(ACTION_NAME, new UnicastPingRequestHandler());
    }

    @Override
    protected void doStart() throws ElasticsearchException {
    }

    @Override
    protected void doStop() throws ElasticsearchException {
    }

    @Override
    protected void doClose() throws ElasticsearchException {
        transportService.removeHandler(ACTION_NAME);
    }

    public void addHostsProvider(UnicastHostsProvider provider) {
        hostsProviders.add(provider);
    }

    public void removeHostsProvider(UnicastHostsProvider provider) {
        hostsProviders.remove(provider);
    }

    @Override
    public void setPingContextProvider(PingContextProvider contextProvider) {
        this.contextProvider = contextProvider;
    }

    /**
     * Clears the list of cached ping responses.
     */
    public void clearTemporalResponses() {
        temporalResponses.clear();
    }

    public PingResponse[] pingAndWait(TimeValue timeout) {
        final AtomicReference<PingResponse[]> response = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        ping(new PingListener() {
            @Override
            public void onPing(PingResponse[] pings) {
                response.set(pings);
                latch.countDown();
            }
        }, timeout);
        try {
            latch.await();
            return response.get();
        } catch (InterruptedException e) {
            return null;
        }
    }

    @Override
    public void ping(final PingListener listener, final TimeValue timeout) throws ElasticsearchException {
        final SendPingsHandler sendPingsHandler = new SendPingsHandler(pingHandlerIdGenerator.incrementAndGet());
        receivedResponses.put(sendPingsHandler.id(), new PingCollection());
        sendPings(timeout, null, sendPingsHandler);
        threadPool.schedule(TimeValue.timeValueMillis(timeout.millis() / 2), ThreadPool.Names.GENERIC, new Runnable() {
            @Override
            public void run() {
                try {
                    sendPings(timeout, null, sendPingsHandler);
                    threadPool.schedule(TimeValue.timeValueMillis(timeout.millis() / 2), ThreadPool.Names.GENERIC, new Runnable() {
                        @Override
                        public void run() {
                            try {
                                sendPings(timeout, TimeValue.timeValueMillis(timeout.millis() / 2), sendPingsHandler);
                                PingCollection responses = receivedResponses.remove(sendPingsHandler.id());
                                sendPingsHandler.close();
                                for (DiscoveryNode node : sendPingsHandler.nodeToDisconnect) {
                                    logger.trace("[{}] disconnecting from {}", sendPingsHandler.id(), node);
                                    transportService.disconnectFromNode(node);
                                }
                                listener.onPing(responses.toArray());
                            } catch (EsRejectedExecutionException ex) {
                                logger.debug("Ping execution rejected", ex);
                            }
                        }
                    });
                } catch (EsRejectedExecutionException ex) {
                    logger.debug("Ping execution rejected", ex);
                }
            }
        });
    }

    class SendPingsHandler {
        private final int id;
        private volatile ExecutorService executor;
        private final Set<DiscoveryNode> nodeToDisconnect = ConcurrentCollections.newConcurrentSet();
        private volatile boolean closed;

        SendPingsHandler(int id) {
            this.id = id;
        }

        public int id() {
            return this.id;
        }

        public boolean isClosed() {
            return this.closed;
        }

        public Executor executor() {
            if (executor == null) {
                ThreadFactory threadFactory = EsExecutors.daemonThreadFactory(settings, "[unicast_connect]");
                executor = EsExecutors.newScaling(0, concurrentConnects, 60, TimeUnit.SECONDS, threadFactory);
            }
            return executor;
        }

        public void close() {
            closed = true;
            ThreadPool.terminate(executor, 0, TimeUnit.SECONDS);
            executor = null;
        }
    }


    void sendPings(final TimeValue timeout, @Nullable TimeValue waitTime, final SendPingsHandler sendPingsHandler) {
        final UnicastPingRequest pingRequest = new UnicastPingRequest();
        pingRequest.id = sendPingsHandler.id();
        pingRequest.timeout = timeout;
        DiscoveryNodes discoNodes = contextProvider.nodes();

        pingRequest.pingResponse = createPingResponse(discoNodes);

        HashSet<DiscoveryNode> nodesToPingSet = new HashSet<>();
        for (PingResponse temporalResponse : temporalResponses) {
            // Only send pings to nodes that have the same cluster name.
            if (clusterName.equals(temporalResponse.clusterName())) {
                nodesToPingSet.add(temporalResponse.node());
            }
        }

        for (UnicastHostsProvider provider : hostsProviders) {
            nodesToPingSet.addAll(provider.buildDynamicNodes());
        }

        // add all possible master nodes that were active in the last known cluster configuration
        for (ObjectCursor<DiscoveryNode> masterNode : discoNodes.getMasterNodes().values()) {
            nodesToPingSet.add(masterNode.value);
        }

        // sort the nodes by likelihood of being an active master
        List<DiscoveryNode> sortedNodesToPing = electMasterService.sortByMasterLikelihood(nodesToPingSet);

        // new add the the unicast targets first
        ArrayList<DiscoveryNode> nodesToPing = Lists.newArrayList(configuredTargetNodes);
        nodesToPing.addAll(sortedNodesToPing);

        final CountDownLatch latch = new CountDownLatch(nodesToPing.size());
        for (final DiscoveryNode node : nodesToPing) {
            // make sure we are connected
            final boolean nodeFoundByAddress;
            DiscoveryNode nodeToSend = discoNodes.findByAddress(node.address());
            if (nodeToSend != null) {
                nodeFoundByAddress = true;
            } else {
                nodeToSend = node;
                nodeFoundByAddress = false;
            }

            if (!transportService.nodeConnected(nodeToSend)) {
                if (sendPingsHandler.isClosed()) {
                    return;
                }
                // if we find on the disco nodes a matching node by address, we are going to restore the connection
                // anyhow down the line if its not connected...
                // if we can't resolve the node, we don't know and we have to clean up after pinging. We do have
                // to make sure we don't disconnect a true node which was temporarily removed from the DiscoveryNodes
                // but will be added again during the pinging. We therefore create a new temporary node
                if (!nodeFoundByAddress) {
                    if (!nodeToSend.id().startsWith(UNICAST_NODE_PREFIX)) {
                        DiscoveryNode tempNode = new DiscoveryNode("",
                                UNICAST_NODE_PREFIX + unicastNodeIdGenerator.incrementAndGet() + "_" + nodeToSend.id() + "#",
                                nodeToSend.getHostName(), nodeToSend.getHostAddress(), nodeToSend.address(), nodeToSend.attributes(), nodeToSend.version()
                        );
                        logger.trace("replacing {} with temp node {}", nodeToSend, tempNode);
                        nodeToSend = tempNode;
                    }
                    sendPingsHandler.nodeToDisconnect.add(nodeToSend);
                }
                // fork the connection to another thread
                final DiscoveryNode finalNodeToSend = nodeToSend;
                sendPingsHandler.executor().execute(new Runnable() {
                    @Override
                    public void run() {
                        if (sendPingsHandler.isClosed()) {
                            return;
                        }
                        boolean success = false;
                        try {
                            // connect to the node, see if we manage to do it, if not, bail
                            if (!nodeFoundByAddress) {
                                logger.trace("[{}] connecting (light) to {}", sendPingsHandler.id(), finalNodeToSend);
                                transportService.connectToNodeLight(finalNodeToSend);
                            } else {
                                logger.trace("[{}] connecting to {}", sendPingsHandler.id(), finalNodeToSend);
                                transportService.connectToNode(finalNodeToSend);
                            }
                            logger.trace("[{}] connected to {}", sendPingsHandler.id(), node);
                            if (receivedResponses.containsKey(sendPingsHandler.id())) {
                                // we are connected and still in progress, send the ping request
                                sendPingRequestToNode(sendPingsHandler.id(), timeout, pingRequest, latch, node, finalNodeToSend);
                            } else {
                                // connect took too long, just log it and bail
                                latch.countDown();
                                logger.trace("[{}] connect to {} was too long outside of ping window, bailing", sendPingsHandler.id(), node);
                            }
                            success = true;
                        } catch (ConnectTransportException e) {
                            // can't connect to the node - this is a more common path!
                            logger.trace("[{}] failed to connect to {}", e, sendPingsHandler.id(), finalNodeToSend);
                        } catch (Throwable e) {
                            logger.warn("[{}] failed send ping to {}", e, sendPingsHandler.id(), finalNodeToSend);
                        } finally {
                            if (!success) {
                                latch.countDown();
                            }
                        }
                    }
                });
            } else {
                sendPingRequestToNode(sendPingsHandler.id(), timeout, pingRequest, latch, node, nodeToSend);
            }
        }
        if (waitTime != null) {
            try {
                latch.await(waitTime.millis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    private void sendPingRequestToNode(final int id, final TimeValue timeout, final UnicastPingRequest pingRequest, final CountDownLatch latch, final DiscoveryNode node, final DiscoveryNode nodeToSend) {
        logger.trace("[{}] sending to {}", id, nodeToSend);
        transportService.sendRequest(nodeToSend, ACTION_NAME, pingRequest, TransportRequestOptions.options().withTimeout((long) (timeout.millis() * 1.25)), new BaseTransportResponseHandler<UnicastPingResponse>() {

            @Override
            public UnicastPingResponse newInstance() {
                return new UnicastPingResponse();
            }

            @Override
            public String executor() {
                return ThreadPool.Names.SAME;
            }

            @Override
            public void handleResponse(UnicastPingResponse response) {
                logger.trace("[{}] received response from {}: {}", id, nodeToSend, Arrays.toString(response.pingResponses));
                try {
                    DiscoveryNodes discoveryNodes = contextProvider.nodes();
                    for (PingResponse pingResponse : response.pingResponses) {
                        if (pingResponse.node().id().equals(discoveryNodes.localNodeId())) {
                            // that's us, ignore
                            continue;
                        }
                        if (!pingResponse.clusterName().equals(clusterName)) {
                            // not part of the cluster
                            logger.debug("[{}] filtering out response from {}, not same cluster_name [{}]", id, pingResponse.node(), pingResponse.clusterName().value());
                            continue;
                        }
                        PingCollection responses = receivedResponses.get(response.id);
                        if (responses == null) {
                            logger.warn("received ping response {} with no matching handler id [{}]", pingResponse, response.id);
                        } else {
                            responses.addPing(pingResponse);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            }

            @Override
            public void handleException(TransportException exp) {
                latch.countDown();
                if (exp instanceof ConnectTransportException) {
                    // ok, not connected...
                    logger.trace("failed to connect to {}", exp, nodeToSend);
                } else {
                    logger.warn("failed to send ping to [{}]", exp, node);
                }
            }
        });
    }

    private UnicastPingResponse handlePingRequest(final UnicastPingRequest request) {
        if (lifecycle.stoppedOrClosed()) {
            throw new ElasticsearchIllegalStateException("received ping request while stopped/closed");
        }
        temporalResponses.add(request.pingResponse);
        threadPool.schedule(TimeValue.timeValueMillis(request.timeout.millis() * 2), ThreadPool.Names.SAME, new Runnable() {
            @Override
            public void run() {
                temporalResponses.remove(request.pingResponse);
            }
        });

        List<PingResponse> pingResponses = newArrayList(temporalResponses);
        pingResponses.add(createPingResponse(contextProvider.nodes()));


        UnicastPingResponse unicastPingResponse = new UnicastPingResponse();
        unicastPingResponse.id = request.id;
        unicastPingResponse.pingResponses = pingResponses.toArray(new PingResponse[pingResponses.size()]);

        return unicastPingResponse;
    }

    class UnicastPingRequestHandler extends BaseTransportRequestHandler<UnicastPingRequest> {

        @Override
        public UnicastPingRequest newInstance() {
            return new UnicastPingRequest();
        }

        @Override
        public String executor() {
            return ThreadPool.Names.SAME;
        }

        @Override
        public void messageReceived(UnicastPingRequest request, TransportChannel channel) throws Exception {
            channel.sendResponse(handlePingRequest(request));
        }
    }

    static class UnicastPingRequest extends TransportRequest {

        int id;

        TimeValue timeout;

        PingResponse pingResponse;

        UnicastPingRequest() {
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            id = in.readInt();
            timeout = readTimeValue(in);
            pingResponse = readPingResponse(in);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeInt(id);
            timeout.writeTo(out);
            pingResponse.writeTo(out);
        }
    }

    private PingResponse createPingResponse(DiscoveryNodes discoNodes) {
        return new PingResponse(discoNodes.localNode(), discoNodes.masterNode(), clusterName, contextProvider.nodeHasJoinedClusterOnce());
    }

    static class UnicastPingResponse extends TransportResponse {

        int id;

        PingResponse[] pingResponses;

        UnicastPingResponse() {
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            id = in.readInt();
            pingResponses = new PingResponse[in.readVInt()];
            for (int i = 0; i < pingResponses.length; i++) {
                pingResponses[i] = readPingResponse(in);
            }
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeInt(id);
            out.writeVInt(pingResponses.length);
            for (PingResponse pingResponse : pingResponses) {
                pingResponse.writeTo(out);
            }
        }
    }
}
