/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher;

import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.coordination.NoMasterBlockService;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.AliasOrIndex;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingNode;
import org.elasticsearch.cluster.routing.RoutingNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.TestShardRouting;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.core.watcher.watch.ClockMock;
import org.elasticsearch.xpack.core.watcher.watch.Watch;
import org.elasticsearch.xpack.core.watcher.watch.WatchStatus;
import org.elasticsearch.xpack.watcher.WatcherIndexingListener.Configuration;
import org.elasticsearch.xpack.watcher.WatcherIndexingListener.ShardAllocationConfiguration;
import org.elasticsearch.xpack.watcher.trigger.TriggerService;
import org.elasticsearch.xpack.watcher.watch.WatchParser;
import org.junit.Before;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import static java.util.Arrays.asList;
import static org.elasticsearch.cluster.routing.ShardRoutingState.RELOCATING;
import static org.elasticsearch.cluster.routing.ShardRoutingState.STARTED;
import static org.elasticsearch.xpack.watcher.WatcherIndexingListener.INACTIVE;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.core.Is.is;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Matchers.anyObject;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class WatcherIndexingListenerTests extends ESTestCase {

    private WatcherIndexingListener listener;
    private WatchParser parser = mock(WatchParser.class);
    private ClockMock clock = new ClockMock();
    private TriggerService triggerService = mock(TriggerService.class);

    private ShardId shardId = mock(ShardId.class);
    private Engine.IndexResult result = mock(Engine.IndexResult.class);
    private Engine.Index operation = mock(Engine.Index.class);
    private Engine.Delete delete = mock(Engine.Delete.class);

    @Before
    public void setup() throws Exception {
        clock.freeze();
        listener = new WatcherIndexingListener(parser, clock, triggerService);

        Map<ShardId, ShardAllocationConfiguration> map = new HashMap<>();
        map.put(shardId, new ShardAllocationConfiguration(0, 1, Collections.singletonList("foo")));

        listener.setConfiguration(new Configuration(Watch.INDEX, map));
    }

    //
    // tests for document level operations
    //
    public void testPreIndexCheckType() throws Exception {
        when(shardId.getIndexName()).thenReturn(Watch.INDEX);
        when(operation.type()).thenReturn(randomAlphaOfLength(10));

        Engine.Index index = listener.preIndex(shardId, operation);
        assertThat(index, is(operation));
        verifyZeroInteractions(parser);
    }

    public void testPreIndexCheckIndex() throws Exception {
        when(operation.type()).thenReturn(Watch.DOC_TYPE);
        when(shardId.getIndexName()).thenReturn(randomAlphaOfLength(10));

        Engine.Index index = listener.preIndex(shardId, operation);
        assertThat(index, is(operation));
        verifyZeroInteractions(parser);
    }

    public void testPreIndexCheckActive() throws Exception {
        listener.setConfiguration(INACTIVE);
        when(operation.type()).thenReturn(Watch.DOC_TYPE);
        when(shardId.getIndexName()).thenReturn(Watch.INDEX);

        Engine.Index index = listener.preIndex(shardId, operation);
        assertThat(index, is(operation));
        verifyZeroInteractions(parser);
    }

    @AwaitsFix(bugUrl = "https://github.com/elastic/elasticsearch/issues/38581")
    public void testPreIndex() throws Exception {
        when(operation.type()).thenReturn(Watch.DOC_TYPE);
        when(operation.id()).thenReturn(randomAlphaOfLength(10));
        when(operation.source()).thenReturn(BytesArray.EMPTY);
        when(shardId.getIndexName()).thenReturn(Watch.INDEX);

        boolean watchActive = randomBoolean();
        boolean isNewWatch = randomBoolean();
        Watch watch = mockWatch("_id", watchActive, isNewWatch);
        when(parser.parseWithSecrets(anyObject(), eq(true), anyObject(), anyObject(), anyObject(), anyLong(), anyLong())).thenReturn(watch);

        Engine.Index returnedOperation = listener.preIndex(shardId, operation);
        assertThat(returnedOperation, is(operation));

        ZonedDateTime now = clock.instant().atZone(ZoneOffset.UTC);
        verify(parser).parseWithSecrets(eq(operation.id()), eq(true), eq(BytesArray.EMPTY), eq(now), anyObject(), anyLong(), anyLong());

        if (isNewWatch) {
            if (watchActive) {
                verify(triggerService).add(eq(watch));
            } else {
                verify(triggerService).remove(eq("_id"));
            }
        }
    }

    // this test emulates an index with 10 shards, and ensures that triggering only happens on a
    // single shard
    public void testPreIndexWatchGetsOnlyTriggeredOnceAcrossAllShards() throws Exception {
        String id = randomAlphaOfLength(10);
        int totalShardCount = randomIntBetween(1, 10);
        boolean watchActive = randomBoolean();
        boolean isNewWatch = randomBoolean();
        Watch watch = mockWatch(id, watchActive, isNewWatch);

        when(shardId.getIndexName()).thenReturn(Watch.INDEX);
        when(operation.type()).thenReturn(Watch.DOC_TYPE);
        when(parser.parseWithSecrets(anyObject(), eq(true), anyObject(), anyObject(), anyObject(), anyLong(), anyLong())).thenReturn(watch);

        for (int idx = 0; idx < totalShardCount; idx++) {
            final Map<ShardId, ShardAllocationConfiguration> localShards = new HashMap<>();
            localShards.put(shardId, new ShardAllocationConfiguration(idx, totalShardCount, Collections.emptyList()));
            Configuration configuration = new Configuration(Watch.INDEX, localShards);
            listener.setConfiguration(configuration);
            listener.preIndex(shardId, operation);
        }

        // no matter how many shards we had, this should have been only called once
        if (isNewWatch) {
            if (watchActive) {
                verify(triggerService, times(1)).add(eq(watch));
            } else {
                verify(triggerService, times(1)).remove(eq(watch.id()));
            }
        }
    }

    private Watch mockWatch(String id, boolean active, boolean isNewWatch) {
        WatchStatus.State watchState = mock(WatchStatus.State.class);
        when(watchState.isActive()).thenReturn(active);

        WatchStatus watchStatus = mock(WatchStatus.class);
        when(watchStatus.state()).thenReturn(watchState);
        if (isNewWatch) {
            when(watchStatus.version()).thenReturn(-1L);
        } else {
            when(watchStatus.version()).thenReturn(randomLong());
        }

        Watch watch = mock(Watch.class);
        when(watch.id()).thenReturn(id);
        when(watch.status()).thenReturn(watchStatus);

        return watch;
    }

    public void testPreIndexCheckParsingException() throws Exception {
        when(operation.type()).thenReturn(Watch.DOC_TYPE);
        String id = randomAlphaOfLength(10);
        when(operation.id()).thenReturn(id);
        when(operation.source()).thenReturn(BytesArray.EMPTY);
        when(shardId.getIndexName()).thenReturn(Watch.INDEX);
        when(parser.parseWithSecrets(anyObject(), eq(true), anyObject(), anyObject(), anyObject(), anyLong(), anyLong()))
                .thenThrow(new IOException("self thrown"));

        ElasticsearchParseException exc = expectThrows(ElasticsearchParseException.class,
                () -> listener.preIndex(shardId, operation));
        assertThat(exc.getMessage(), containsString("Could not parse watch"));
        assertThat(exc.getMessage(), containsString(id));
    }

    public void testPostIndexRemoveTriggerOnException() throws Exception {
        when(operation.id()).thenReturn("_id");
        when(operation.type()).thenReturn(Watch.DOC_TYPE);
        when(shardId.getIndexName()).thenReturn(Watch.INDEX);

        listener.postIndex(shardId, operation, new ElasticsearchParseException("whatever"));
        verify(triggerService).remove(eq("_id"));
    }

    public void testPostIndexDontInvokeForOtherDocuments() throws Exception {
        when(operation.id()).thenReturn("_id");
        when(operation.type()).thenReturn(Watch.DOC_TYPE);
        when(shardId.getIndexName()).thenReturn("anything");
        when(result.getResultType()).thenReturn(Engine.Result.Type.SUCCESS);

        listener.postIndex(shardId, operation, new ElasticsearchParseException("whatever"));
        verifyZeroInteractions(triggerService);
    }

    public void testPreDeleteCheckActive() throws Exception {
        listener.setConfiguration(INACTIVE);
        listener.preDelete(shardId, delete);

        verifyZeroInteractions(triggerService);
    }

    public void testPreDeleteCheckIndex() throws Exception {
        when(shardId.getIndexName()).thenReturn(randomAlphaOfLength(10));

        listener.preDelete(shardId, delete);

        verifyZeroInteractions(triggerService);
    }

    public void testPreDeleteCheckType() throws Exception {
        when(shardId.getIndexName()).thenReturn(Watch.INDEX);
        when(delete.type()).thenReturn(randomAlphaOfLength(10));

        listener.preDelete(shardId, delete);

        verifyZeroInteractions(triggerService);
    }

    public void testPreDelete() throws Exception {
        when(shardId.getIndexName()).thenReturn(Watch.INDEX);
        when(delete.type()).thenReturn(Watch.DOC_TYPE);
        when(delete.id()).thenReturn("_id");

        listener.preDelete(shardId, delete);

        verify(triggerService).remove(eq("_id"));
    }

    //
    // tests for cluster state updates
    //
    public void testClusterChangedNoMetadata() throws Exception {
        ClusterState state = mockClusterState(randomAlphaOfLength(10));
        listener.clusterChanged(new ClusterChangedEvent("any", state, state));

        assertThat(listener.getConfiguration().isIndexAndActive(Watch.INDEX), is(true));
    }

    public void testClusterChangedNoWatchIndex() throws Exception {
        Map<ShardId, ShardAllocationConfiguration> map = new HashMap<>();
        map.put(shardId, new ShardAllocationConfiguration(0, 1, Collections.singletonList("foo")));
        Configuration randomConfiguration = new Configuration(randomAlphaOfLength(10), map);
        listener.setConfiguration(randomConfiguration);

        ClusterState clusterState = mockClusterState(null);
        ClusterChangedEvent clusterChangedEvent = mock(ClusterChangedEvent.class);
        when(clusterChangedEvent.metaDataChanged()).thenReturn(true);
        when(clusterChangedEvent.state()).thenReturn(clusterState);

        listener.clusterChanged(clusterChangedEvent);

        assertThat(listener.getConfiguration(), equalTo(INACTIVE));
    }

    public void testClusterChangedWatchAliasChanged() throws Exception {
        String newActiveWatchIndex = randomAlphaOfLength(10);
        RoutingTable routingTable = mock(RoutingTable.class);
        when(routingTable.hasIndex(eq(newActiveWatchIndex))).thenReturn(true);

        ClusterState currentClusterState = mockClusterState(newActiveWatchIndex);
        when(currentClusterState.routingTable()).thenReturn(routingTable);
        DiscoveryNodes nodes = DiscoveryNodes.builder().add(newNode("node_1"))
                .localNodeId("node_1").build();
        when(currentClusterState.getNodes()).thenReturn(nodes);
        RoutingNodes routingNodes = mock(RoutingNodes.class);
        RoutingNode routingNode = mock(RoutingNode.class);
        boolean emptyShards = randomBoolean();

        if (emptyShards) {
            when(routingNode.shardsWithState(eq(newActiveWatchIndex), any()))
                    .thenReturn(Collections.emptyList());
        } else {
            Index index = new Index(newActiveWatchIndex, "uuid");
            ShardId shardId = new ShardId(index, 0);
            ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, "node_1", true,
                    STARTED);
            List<ShardRouting> routing = Collections.singletonList(shardRouting);
            when(routingNode.shardsWithState(eq(newActiveWatchIndex), eq(STARTED),  eq(RELOCATING)))
                    .thenReturn(routing);
            when(routingTable.allShards(eq(newActiveWatchIndex))).thenReturn(routing);
            IndexRoutingTable indexRoutingTable = IndexRoutingTable.builder(index)
                    .addShard(shardRouting).build();
            when(routingTable.index(newActiveWatchIndex)).thenReturn(indexRoutingTable);
        }

        when(routingNodes.node(eq("node_1"))).thenReturn(routingNode);
        when(currentClusterState.getRoutingNodes()).thenReturn(routingNodes);

        ClusterState previousClusterState = mockClusterState(randomAlphaOfLength(8));
        when(previousClusterState.routingTable()).thenReturn(routingTable);

        ClusterChangedEvent event = new ClusterChangedEvent("something", currentClusterState,
                previousClusterState);
        listener.clusterChanged(event);

        if (emptyShards) {
            assertThat(listener.getConfiguration(), is(INACTIVE));
        } else {
            assertThat(listener.getConfiguration().isIndexAndActive(newActiveWatchIndex),
                    is(true));
        }
    }

    public void testClusterChangedNoRoutingChanges() throws Exception {
        Index index = new Index(Watch.INDEX, "foo");
        IndexRoutingTable watchRoutingTable = IndexRoutingTable.builder(index).build();
        ClusterState previousState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1")
                        .add(newNode("node_1")))
                .routingTable(RoutingTable.builder().add(watchRoutingTable).build())
                .build();

        ClusterState currentState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1")
                        .add(newNode("node_1")).add(newNode("node_2")))
                .routingTable(RoutingTable.builder().add(watchRoutingTable).build())
                .build();

        Configuration configuration = listener.getConfiguration();
        assertThat(configuration.isIndexAndActive(Watch.INDEX), is(true));

        ClusterChangedEvent event = new ClusterChangedEvent("something", currentState,
                previousState);
        listener.clusterChanged(event);

        assertThat(listener.getConfiguration(), is(configuration));
        assertThat(listener.getConfiguration().isIndexAndActive(Watch.INDEX), is(true));
    }

    // a shard is marked as relocating, no change in the routing yet (replica might be added,
    // shard might be offloaded)
    public void testCheckAllocationIdsOnShardStarted() throws Exception {
        Index index = new Index(Watch.INDEX, "foo");
        ShardId shardId = new ShardId(index, 0);
        ShardRoutingState randomState = randomFrom(STARTED, RELOCATING);
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, "current", randomState == RELOCATING ? "other" : null, true,
                randomState);
        IndexRoutingTable indexRoutingTable = IndexRoutingTable.builder(index)
                .addShard(shardRouting).build();

        Map<ShardId, ShardAllocationConfiguration> allocationIds =
                listener.getLocalShardAllocationIds(asList(shardRouting), indexRoutingTable);

        assertThat(allocationIds.size(), is(1));
        assertThat(allocationIds.get(shardId).index, is(0));
        assertThat(allocationIds.get(shardId).shardCount, is(1));
    }

    public void testCheckAllocationIdsWithoutShards() throws Exception {
        Index index = new Index(Watch.INDEX, "foo");
        ShardId shardId = new ShardId(index, 0);
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, "other", true,
                STARTED);
        IndexRoutingTable indexRoutingTable = IndexRoutingTable.builder(index)
                .addShard(shardRouting).build();

        Map<ShardId, ShardAllocationConfiguration> allocationIds =
                listener.getLocalShardAllocationIds(Collections.emptyList(), indexRoutingTable);
        assertThat(allocationIds.size(), is(0));
    }

    public void testCheckAllocationIdsWithSeveralShards() {
        // setup 5 shards, one replica, 10 shards total, all started
        Index index = new Index(Watch.INDEX, "foo");
        ShardId firstShardId = new ShardId(index, 0);
        ShardId secondShardId = new ShardId(index, 1);

        List<ShardRouting> localShards = new ArrayList<>();
        localShards.add(TestShardRouting.newShardRouting(firstShardId, "node1", true, STARTED));
        localShards.add(TestShardRouting.newShardRouting(secondShardId, "node1", true, STARTED));

        IndexRoutingTable indexRoutingTable = IndexRoutingTable.builder(index)
                .addShard(localShards.get(0))
                .addShard(localShards.get(1))
                .addShard(TestShardRouting.newShardRouting(firstShardId, "node2", true, STARTED))
                .addShard(TestShardRouting.newShardRouting(secondShardId, "node2", true, STARTED))
                .build();

        Map<ShardId, ShardAllocationConfiguration> allocationIds =
                listener.getLocalShardAllocationIds(localShards, indexRoutingTable);
        assertThat(allocationIds.size(), is(2));
    }

    // no matter how many copies of a shard exist, a watch should always be triggered exactly once
    public void testShardConfigurationShouldBeTriggeredExactlyOnce() throws Exception {
        // random number of shards
        int numberOfShards = randomIntBetween(1, 20);
        int numberOfDocuments = randomIntBetween(1, 10000);
        BitSet bitSet = new BitSet(numberOfDocuments);
        logger.info("Testing [{}] documents with [{}] shards", numberOfDocuments, numberOfShards);

        for (int currentShardId = 0; currentShardId < numberOfShards; currentShardId++) {
            ShardAllocationConfiguration sac = new ShardAllocationConfiguration(currentShardId,
                    numberOfShards, Collections.emptyList());

            for (int i = 0; i < numberOfDocuments; i++) {
                boolean shouldBeTriggered = sac.shouldBeTriggered("watch_" + i);
                boolean hasAlreadyBeenTriggered = bitSet.get(i);
                if (shouldBeTriggered) {
                    String message = String.format(Locale.ROOT, "Watch [%s] has already been " +
                            "triggered", i);
                    assertThat(message, hasAlreadyBeenTriggered, is(false));
                    bitSet.set(i);
                }
            }
        }

        assertThat(bitSet.cardinality(), is(numberOfDocuments));
    }

    // ensure that non data nodes, deal properly with this cluster state listener
    public void testOnNonDataNodes() {
        listener.setConfiguration(INACTIVE);
        Index index = new Index(Watch.INDEX, "foo");
        ShardId shardId = new ShardId(index, 0);
        ShardRouting shardRouting = TestShardRouting.newShardRouting(shardId, "node2", true, STARTED);
        IndexRoutingTable.Builder indexRoutingTable = IndexRoutingTable.builder(index).addShard(shardRouting);

        DiscoveryNode node1 = new DiscoveryNode("node_1", ESTestCase.buildNewFakeTransportAddress(),
                Collections.emptyMap(), new HashSet<>(Collections.singletonList(
                        randomFrom(DiscoveryNode.Role.INGEST, DiscoveryNode.Role.MASTER))),
                Version.CURRENT);

        DiscoveryNode node2 = new DiscoveryNode("node_2", ESTestCase.buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(Collections.singletonList(DiscoveryNode.Role.DATA)), Version.CURRENT);

        DiscoveryNode node3 = new DiscoveryNode("node_3", ESTestCase.buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(Collections.singletonList(DiscoveryNode.Role.DATA)), Version.CURRENT);

        IndexMetaData.Builder indexMetaDataBuilder = createIndexBuilder(Watch.INDEX, 1 ,0);

        ClusterState previousState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(indexMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(node1).add(node2).add(node3))
                .routingTable(RoutingTable.builder().add(indexRoutingTable).build())
                .build();

        IndexMetaData.Builder newIndexMetaDataBuilder = createIndexBuilder(Watch.INDEX, 1, 1);

        ShardRouting replicaShardRouting = TestShardRouting.newShardRouting(shardId, "node3", false, STARTED);
        IndexRoutingTable.Builder newRoutingTable = IndexRoutingTable.builder(index)
                .addShard(shardRouting)
                .addShard(replicaShardRouting);
        ClusterState currentState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(newIndexMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(node1).add(node2).add(node3))
                .routingTable(RoutingTable.builder().add(newRoutingTable).build())
                .build();

        ClusterChangedEvent event = new ClusterChangedEvent("something", currentState, previousState);
        listener.clusterChanged(event);
        assertThat(listener.getConfiguration(), is(INACTIVE));
    }

    public void testListenerWorksIfOtherIndicesChange() throws Exception {
        DiscoveryNode node1 = newNode("node_1");
        DiscoveryNode node2 = newNode("node_2");

        Index index = new Index("random-index", "foo");
        ShardId firstShardId = new ShardId(index, 0);

        IndexMetaData.Builder indexMetaDataBuilder = createIndexBuilder("random-index", 2, 1);

        IndexRoutingTable.Builder indexRoutingTable = IndexRoutingTable.builder(index)
                .addShard(TestShardRouting.newShardRouting(firstShardId, "node_1", true, STARTED))
                .addShard(TestShardRouting.newShardRouting(firstShardId, "node_2", false, STARTED));

        ClusterState previousState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(indexMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(node1).add(node2))
                .routingTable(RoutingTable.builder().add(indexRoutingTable).build())
                .build();

        IndexMetaData.Builder currentMetaDataBuilder = createIndexBuilder(Watch.INDEX, 2, 1);

        boolean useWatchIndex = randomBoolean();
        String indexName = useWatchIndex ? Watch.INDEX : "other-index-name";
        Index otherIndex = new Index(indexName, "foo");
        ShardId watchShardId = new ShardId(otherIndex, 0);

        IndexRoutingTable.Builder currentRoutingTable = IndexRoutingTable.builder(otherIndex)
                .addShard(TestShardRouting.newShardRouting(watchShardId, "node_1", true, STARTED))
                .addShard(TestShardRouting.newShardRouting(watchShardId, "node_2", false, STARTED));

        ClusterState currentState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(currentMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(node1).add(node2))
                .routingTable(RoutingTable.builder().add(currentRoutingTable).build())
                .build();

        listener.setConfiguration(INACTIVE);
        ClusterChangedEvent event = new ClusterChangedEvent("something", currentState, previousState);
        listener.clusterChanged(event);
        if (useWatchIndex) {
            assertThat(listener.getConfiguration(), is(not(INACTIVE)));
        } else {
            assertThat(listener.getConfiguration(), is(INACTIVE));
        }
    }

    // 4 nodes, each node has one shard, now node 3 fails, which means only one node should
    // reload, where as two should not
    // this test emulates on of those two nodes
    public void testThatShardConfigurationIsNotReloadedNonAffectedShardsChange() {
        listener.setConfiguration(INACTIVE);

        DiscoveryNode node1 = newNode("node_1");
        DiscoveryNode node2 = newNode("node_2");
        DiscoveryNode node3 = newNode("node_3");
        DiscoveryNode node4 = newNode("node_4");

        String localNode = randomFrom("node_1", "node_2");

        Index index = new Index(Watch.INDEX, "foo");
        ShardId firstShardId = new ShardId(index, 0);
        ShardId secondShardId = new ShardId(index, 1);

        IndexMetaData.Builder indexMetaDataBuilder = createIndexBuilder(Watch.INDEX, 2, 1);

        ShardRouting firstShardRoutingPrimary = TestShardRouting.newShardRouting(firstShardId, "node_1", true, STARTED);
        ShardRouting firstShardRoutingReplica = TestShardRouting.newShardRouting(firstShardId, "node_2", false, STARTED);
        ShardRouting secondShardRoutingPrimary = TestShardRouting.newShardRouting(secondShardId, "node_3", true, STARTED);
        ShardRouting secondShardRoutingReplica = TestShardRouting.newShardRouting(secondShardId, "node_4", false, STARTED);
        IndexRoutingTable.Builder indexRoutingTable = IndexRoutingTable.builder(index)
                .addShard(firstShardRoutingPrimary)
                .addShard(firstShardRoutingReplica)
                .addShard(secondShardRoutingPrimary)
                .addShard(secondShardRoutingReplica);

        ClusterState previousState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(indexMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId(localNode)
                        .add(node1).add(node2).add(node3).add(node4))
                .routingTable(RoutingTable.builder().add(indexRoutingTable).build())
                .build();

        ClusterState emptyState = ClusterState.builder(new ClusterName("my-cluster"))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId(localNode)
                        .add(node1).add(node2).add(node3).add(node4))
                .build();

        ClusterChangedEvent event = new ClusterChangedEvent("something", previousState, emptyState);
        listener.clusterChanged(event);
        Configuration configuration = listener.getConfiguration();
        assertThat(configuration, is(not(INACTIVE)));

        // now create a cluster state where node 4 is missing
        IndexMetaData.Builder newIndexMetaDataBuilder = createIndexBuilder(Watch.INDEX, 2, 1);

        IndexRoutingTable.Builder newRoutingTable = IndexRoutingTable.builder(index)
                .addShard(firstShardRoutingPrimary)
                .addShard(firstShardRoutingReplica)
                .addShard(secondShardRoutingPrimary);

        ClusterState currentState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(newIndexMetaDataBuilder))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId(localNode)
                        .add(node1).add(node2).add(node3).add(node4))
                .routingTable(RoutingTable.builder().add(newRoutingTable).build())
                .build();

        ClusterChangedEvent nodeGoneEvent = new ClusterChangedEvent("something", currentState, previousState);
        listener.clusterChanged(nodeGoneEvent);

        // ensure no configuration replacement has happened
        assertThat(listener.getConfiguration(), is(configuration));
    }

    // if the creates a .watches alias that points to two indices, set watcher to be inactive
    public void testWithAliasPointingToTwoIndicesSetsWatcherInactive() {
        listener.setConfiguration(INACTIVE);
        DiscoveryNode node1 = newNode("node_1");

        // index foo pointing to .watches
        Index fooIndex = new Index("foo", "someuuid");
        ShardId fooShardId = new ShardId(fooIndex, 0);
        ShardRouting fooShardRouting = TestShardRouting.newShardRouting(fooShardId, node1.getId(), true, STARTED);
        IndexRoutingTable.Builder fooIndexRoutingTable = IndexRoutingTable.builder(fooIndex).addShard(fooShardRouting);

        // regular cluster state with correct single alias pointing to watches index
        ClusterState previousState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(createIndexBuilder("foo", 1, 0)
                        .putAlias(AliasMetaData.builder(Watch.INDEX))))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1").add(node1))
                .routingTable(RoutingTable.builder().add(fooIndexRoutingTable).build())
                .build();

        // index bar pointing to .watches
        Index barIndex = new Index("bar", "someuuid2");
        ShardId barShardId = new ShardId(fooIndex, 0);
        IndexMetaData.Builder barIndexMetaData = createIndexBuilder("bar", 1, 0).putAlias(AliasMetaData.builder(Watch.INDEX));
        ShardRouting barShardRouting = TestShardRouting.newShardRouting(barShardId, node1.getId(), true, STARTED);
        IndexRoutingTable.Builder barIndexRoutingTable = IndexRoutingTable.builder(barIndex).addShard(barShardRouting);

        // cluster state with two indices pointing to the .watches index
        ClusterState currentState = ClusterState.builder(new ClusterName("my-cluster"))
                .metaData(MetaData.builder().put(createIndexBuilder("foo", 1, 0)
                        .putAlias(AliasMetaData.builder(Watch.INDEX)))
                        .put(barIndexMetaData))
                .nodes(new DiscoveryNodes.Builder().masterNodeId("node_1").localNodeId("node_1")
                        .add(node1))
                .routingTable(RoutingTable.builder()
                        .add(IndexRoutingTable.builder(fooIndex).addShard(fooShardRouting))
                        .add(barIndexRoutingTable).build())
                .build();

        ClusterChangedEvent nodeGoneEvent = new ClusterChangedEvent("something", currentState, previousState);
        listener.clusterChanged(nodeGoneEvent);

        // ensure no configuration replacement has happened
        assertThat(listener.getConfiguration(), is(INACTIVE));
    }

    public void testThatIndexingListenerBecomesInactiveWithoutMasterNode() {
        ClusterState clusterStateWithMaster = mockClusterState(Watch.INDEX);
        ClusterState clusterStateWithoutMaster = mockClusterState(Watch.INDEX);
        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node_1").add(newNode("node_1")).build();
        when(clusterStateWithoutMaster.nodes()).thenReturn(nodes);

        assertThat(listener.getConfiguration(), is(not(INACTIVE)));
        listener.clusterChanged(new ClusterChangedEvent("something", clusterStateWithoutMaster, clusterStateWithMaster));

        assertThat(listener.getConfiguration(), is(INACTIVE));
    }

    public void testThatIndexingListenerBecomesInactiveOnClusterBlock() {
        ClusterState clusterState = mockClusterState(Watch.INDEX);
        ClusterState clusterStateWriteBlock = mockClusterState(Watch.INDEX);
        ClusterBlocks clusterBlocks = ClusterBlocks.builder().addGlobalBlock(NoMasterBlockService.NO_MASTER_BLOCK_WRITES).build();
        when(clusterStateWriteBlock.getBlocks()).thenReturn(clusterBlocks);

        assertThat(listener.getConfiguration(), is(not(INACTIVE)));
        listener.clusterChanged(new ClusterChangedEvent("something", clusterStateWriteBlock, clusterState));

        assertThat(listener.getConfiguration(), is(INACTIVE));
    }

    //
    // helper methods
    //
    /**
     * create a mock cluster state, the returns the specified index as watch index
     */
    private ClusterState mockClusterState(String watchIndex) {
        MetaData metaData = mock(MetaData.class);
        if (watchIndex == null) {
            when(metaData.getAliasAndIndexLookup()).thenReturn(Collections.emptySortedMap());
        } else {
            SortedMap<String, AliasOrIndex> indices = new TreeMap<>();

            IndexMetaData indexMetaData = mock(IndexMetaData.class);
            when(indexMetaData.getIndex()).thenReturn(new Index(watchIndex, randomAlphaOfLength(10)));
            indices.put(watchIndex, new AliasOrIndex.Index(indexMetaData));

            // now point the alias, if the watch index is not .watches
            if (watchIndex.equals(Watch.INDEX) == false) {
                AliasMetaData aliasMetaData = mock(AliasMetaData.class);
                when(aliasMetaData.alias()).thenReturn(watchIndex);
                indices.put(Watch.INDEX, new AliasOrIndex.Alias(aliasMetaData, indexMetaData));
            }

            when(metaData.getAliasAndIndexLookup()).thenReturn(indices);
        }

        ClusterState clusterState = mock(ClusterState.class);
        when(clusterState.metaData()).thenReturn(metaData);

        DiscoveryNodes nodes = DiscoveryNodes.builder().localNodeId("node_1").masterNodeId("node_1").add(newNode("node_1")).build();
        when(clusterState.nodes()).thenReturn(nodes);
        when(clusterState.getBlocks()).thenReturn(ClusterBlocks.EMPTY_CLUSTER_BLOCK);

        return clusterState;
    }

    private IndexMetaData.Builder createIndexBuilder(String name, int numberOfShards,
                                                     int numberOfReplicas) {
        return IndexMetaData.builder(name)
                .settings(Settings.builder()
                        .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, numberOfShards)
                        .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, numberOfReplicas)
                        .put(IndexMetaData.SETTING_VERSION_CREATED, Version.CURRENT)
                );
    }

    private static DiscoveryNode newNode(String nodeId) {
        return new DiscoveryNode(nodeId, ESTestCase.buildNewFakeTransportAddress(), Collections.emptyMap(),
                new HashSet<>(asList(DiscoveryNode.Role.values())), Version.CURRENT);
    }
}
