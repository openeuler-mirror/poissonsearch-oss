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

package org.elasticsearch.recovery;

import com.carrotsearch.hppc.IntHashSet;
import com.carrotsearch.hppc.procedures.IntProcedure;
import org.apache.lucene.index.IndexFileNames;
import org.apache.lucene.util.English;
import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.indices.stats.IndexShardStats;
import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.admin.indices.stats.ShardStats;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.command.MoveAllocationCommand;
import org.elasticsearch.cluster.routing.allocation.decider.EnableAllocationDecider;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.seqno.SeqNoStats;
import org.elasticsearch.index.seqno.SequenceNumbersService;
import org.elasticsearch.index.shard.IndexEventListener;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardState;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.recovery.PeerRecoveryTargetService;
import org.elasticsearch.indices.recovery.RecoveryFileChunkRequest;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.test.BackgroundIndexer;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.MockIndexEventListener;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportException;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.transport.TransportRequestOptions;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.elasticsearch.index.IndexSettings.INDEX_SEQ_NO_CHECKPOINT_SYNC_INTERVAL;
import static org.elasticsearch.index.query.QueryBuilders.matchAllQuery;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertSearchHits;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@ClusterScope(scope = Scope.TEST, numDataNodes = 0)
@TestLogging("_root:DEBUG,org.elasticsearch.indices.recovery:TRACE,org.elasticsearch.index.shard.service:TRACE")
@LuceneTestCase.AwaitsFix(bugUrl = "primary relocation needs to transfer the global check point. otherwise the new primary sends a " +
    "an unknown global checkpoint during sync, causing assertions to trigger")
public class RelocationIT extends ESIntegTestCase {
    private final TimeValue ACCEPTABLE_RELOCATION_TIME = new TimeValue(5, TimeUnit.MINUTES);

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(MockTransportService.TestPlugin.class, MockIndexEventListener.TestPlugin.class);
    }

    @Override
    public Settings indexSettings() {
        return Settings.builder().put(super.indexSettings())
            .put(INDEX_SEQ_NO_CHECKPOINT_SYNC_INTERVAL.getKey(), "200ms").build();
    }

    @Override
    protected void beforeIndexDeletion() throws Exception {
        super.beforeIndexDeletion();
        assertBusy(() -> {
            IndicesStatsResponse stats = client().admin().indices().prepareStats().clear().get();
            for (IndexStats indexStats : stats.getIndices().values()) {
                for (IndexShardStats indexShardStats : indexStats.getIndexShards().values()) {
                    Optional<ShardStats> maybePrimary = Stream.of(indexShardStats.getShards())
                        .filter(s -> s.getShardRouting().active() && s.getShardRouting().primary())
                        .findFirst();
                    if (maybePrimary.isPresent() == false) {
                        continue;
                    }
                    ShardStats primary = maybePrimary.get();
                    final SeqNoStats primarySeqNoStats = primary.getSeqNoStats();
                    assertThat(primary.getShardRouting() + " should have set the global checkpoint",
                        primarySeqNoStats.getGlobalCheckpoint(), not(equalTo(SequenceNumbersService.UNASSIGNED_SEQ_NO)));
                    for (ShardStats shardStats : indexShardStats) {
                        final SeqNoStats seqNoStats = shardStats.getSeqNoStats();
                        assertThat(shardStats.getShardRouting() + " local checkpoint mismatch",
                            seqNoStats.getLocalCheckpoint(), equalTo(primarySeqNoStats.getLocalCheckpoint()));

                        assertThat(shardStats.getShardRouting() + " global checkpoint mismatch",
                            seqNoStats.getGlobalCheckpoint(), equalTo(primarySeqNoStats.getGlobalCheckpoint()));
                        assertThat(shardStats.getShardRouting() + " max seq no mismatch",
                            seqNoStats.getMaxSeqNo(), equalTo(primarySeqNoStats.getMaxSeqNo()));
                    }
                }
            }
        });
    }

    public void testSimpleRelocationNoIndexing() {
        logger.info("--> starting [node1] ...");
        final String node_1 = internalCluster().startNode();

        logger.info("--> creating test index ...");
        prepareCreate("test", Settings.builder()
                .put("index.number_of_shards", 1)
                .put("index.number_of_replicas", 0)
        ).get();

        logger.info("--> index 10 docs");
        for (int i = 0; i < 10; i++) {
            client().prepareIndex("test", "type", Integer.toString(i)).setSource("field", "value" + i).execute().actionGet();
        }
        logger.info("--> flush so we have an actual index");
        client().admin().indices().prepareFlush().execute().actionGet();
        logger.info("--> index more docs so we have something in the translog");
        for (int i = 10; i < 20; i++) {
            client().prepareIndex("test", "type", Integer.toString(i)).setSource("field", "value" + i).execute().actionGet();
        }

        logger.info("--> verifying count");
        client().admin().indices().prepareRefresh().execute().actionGet();
        assertThat(client().prepareSearch("test").setSize(0).execute().actionGet().getHits().totalHits(), equalTo(20L));

        logger.info("--> start another node");
        final String node_2 = internalCluster().startNode();
        ClusterHealthResponse clusterHealthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForNodes("2").execute().actionGet();
        assertThat(clusterHealthResponse.isTimedOut(), equalTo(false));

        logger.info("--> relocate the shard from node1 to node2");
        client().admin().cluster().prepareReroute()
                .add(new MoveAllocationCommand("test", 0, node_1, node_2))
                .execute().actionGet();

        clusterHealthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForNoRelocatingShards(true).setTimeout(ACCEPTABLE_RELOCATION_TIME).execute().actionGet();
        assertThat(clusterHealthResponse.isTimedOut(), equalTo(false));
        clusterHealthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForNoRelocatingShards(true).setTimeout(ACCEPTABLE_RELOCATION_TIME).execute().actionGet();
        assertThat(clusterHealthResponse.isTimedOut(), equalTo(false));

        logger.info("--> verifying count again...");
        client().admin().indices().prepareRefresh().execute().actionGet();
        assertThat(client().prepareSearch("test").setSize(0).execute().actionGet().getHits().totalHits(), equalTo(20L));
    }

    @TestLogging("action.index:TRACE,action.bulk:TRACE,action.search:TRACE")
    public void testRelocationWhileIndexingRandom() throws Exception {
        int numberOfRelocations = scaledRandomIntBetween(1, rarely() ? 10 : 4);
        int numberOfReplicas = randomBoolean() ? 0 : 1;
        int numberOfNodes = numberOfReplicas == 0 ? 2 : 3;

        logger.info("testRelocationWhileIndexingRandom(numRelocations={}, numberOfReplicas={}, numberOfNodes={})", numberOfRelocations, numberOfReplicas, numberOfNodes);

        String[] nodes = new String[numberOfNodes];
        logger.info("--> starting [node1] ...");
        nodes[0] = internalCluster().startNode();

        logger.info("--> creating test index ...");
        prepareCreate("test", Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", numberOfReplicas)
        ).get();


        for (int i = 1; i < numberOfNodes; i++) {
            logger.info("--> starting [node{}] ...", i + 1);
            nodes[i] = internalCluster().startNode();
            if (i != numberOfNodes - 1) {
                ClusterHealthResponse healthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID)
                        .setWaitForNodes(Integer.toString(i + 1)).setWaitForGreenStatus().execute().actionGet();
                assertThat(healthResponse.isTimedOut(), equalTo(false));
            }
        }

        int numDocs = scaledRandomIntBetween(200, 2500);
        try (BackgroundIndexer indexer = new BackgroundIndexer("test", "type1", client(), numDocs)) {
            logger.info("--> waiting for {} docs to be indexed ...", numDocs);
            waitForDocs(numDocs, indexer);
            logger.info("--> {} docs indexed", numDocs);

            logger.info("--> starting relocations...");
            int nodeShiftBased = numberOfReplicas; // if we have replicas shift those
            for (int i = 0; i < numberOfRelocations; i++) {
                int fromNode = (i % 2);
                int toNode = fromNode == 0 ? 1 : 0;
                fromNode += nodeShiftBased;
                toNode += nodeShiftBased;
                numDocs = scaledRandomIntBetween(200, 1000);
                logger.debug("--> Allow indexer to index [{}] documents", numDocs);
                indexer.continueIndexing(numDocs);
                logger.info("--> START relocate the shard from {} to {}", nodes[fromNode], nodes[toNode]);
                client().admin().cluster().prepareReroute()
                        .add(new MoveAllocationCommand("test", 0, nodes[fromNode], nodes[toNode]))
                        .get();
                if (rarely()) {
                    logger.debug("--> flushing");
                    client().admin().indices().prepareFlush().get();
                }
                ClusterHealthResponse clusterHealthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForNoRelocatingShards(true).setTimeout(ACCEPTABLE_RELOCATION_TIME).execute().actionGet();
                assertThat(clusterHealthResponse.isTimedOut(), equalTo(false));
                clusterHealthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID).setWaitForNoRelocatingShards(true).setTimeout(ACCEPTABLE_RELOCATION_TIME).execute().actionGet();
                assertThat(clusterHealthResponse.isTimedOut(), equalTo(false));
                indexer.pauseIndexing();
                logger.info("--> DONE relocate the shard from {} to {}", fromNode, toNode);
            }
            logger.info("--> done relocations");
            logger.info("--> waiting for indexing threads to stop ...");
            indexer.stop();
            logger.info("--> indexing threads stopped");

            logger.info("--> refreshing the index");
            client().admin().indices().prepareRefresh("test").execute().actionGet();
            logger.info("--> searching the index");
            boolean ranOnce = false;
            for (int i = 0; i < 10; i++) {
                try {
                    logger.info("--> START search test round {}", i + 1);
                    SearchHits hits = client().prepareSearch("test").setQuery(matchAllQuery()).setSize((int) indexer.totalIndexedDocs()).storedFields().execute().actionGet().getHits();
                    ranOnce = true;
                    if (hits.totalHits() != indexer.totalIndexedDocs()) {
                        int[] hitIds = new int[(int) indexer.totalIndexedDocs()];
                        for (int hit = 0; hit < indexer.totalIndexedDocs(); hit++) {
                            hitIds[hit] = hit + 1;
                        }
                        IntHashSet set = IntHashSet.from(hitIds);
                        for (SearchHit hit : hits.hits()) {
                            int id = Integer.parseInt(hit.id());
                            if (!set.remove(id)) {
                                logger.error("Extra id [{}]", id);
                            }
                        }
                        set.forEach((IntProcedure) value -> {
                            logger.error("Missing id [{}]", value);
                        });
                    }
                    assertThat(hits.totalHits(), equalTo(indexer.totalIndexedDocs()));
                    logger.info("--> DONE search test round {}", i + 1);
                } catch (SearchPhaseExecutionException ex) {
                    // TODO: the first run fails with this failure, waiting for relocating nodes set to 0 is not enough?
                    logger.warn("Got exception while searching.", ex);
                }
            }
            if (!ranOnce) {
                fail();
            }
        }
    }

    public void testRelocationWhileRefreshing() throws Exception {
        int numberOfRelocations = scaledRandomIntBetween(1, rarely() ? 10 : 4);
        int numberOfReplicas = randomBoolean() ? 0 : 1;
        int numberOfNodes = numberOfReplicas == 0 ? 2 : 3;

        logger.info("testRelocationWhileIndexingRandom(numRelocations={}, numberOfReplicas={}, numberOfNodes={})", numberOfRelocations, numberOfReplicas, numberOfNodes);

        String[] nodes = new String[numberOfNodes];
        logger.info("--> starting [node_0] ...");
        nodes[0] = internalCluster().startNode();

        logger.info("--> creating test index ...");
        prepareCreate("test", Settings.builder()
            .put("index.number_of_shards", 1)
            .put("index.number_of_replicas", numberOfReplicas)
            .put("index.refresh_interval", -1) // we want to control refreshes c
        ).get();

        for (int i = 1; i < numberOfNodes; i++) {
            logger.info("--> starting [node_{}] ...", i);
            nodes[i] = internalCluster().startNode();
            if (i != numberOfNodes - 1) {
                ClusterHealthResponse healthResponse = client().admin().cluster().prepareHealth().setWaitForEvents(Priority.LANGUID)
                        .setWaitForNodes(Integer.toString(i + 1)).setWaitForGreenStatus().execute().actionGet();
                assertThat(healthResponse.isTimedOut(), equalTo(false));
            }
        }

        final Semaphore postRecoveryShards = new Semaphore(0);
        final IndexEventListener listener = new IndexEventListener() {
            @Override
            public void indexShardStateChanged(IndexShard indexShard, @Nullable IndexShardState previousState, IndexShardState currentState, @Nullable String reason) {
                if (currentState == IndexShardState.POST_RECOVERY) {
                    postRecoveryShards.release();
                }
            }
        };
        for (MockIndexEventListener.TestEventListener eventListener : internalCluster().getInstances(MockIndexEventListener.TestEventListener.class)) {
            eventListener.setNewDelegate(listener);
        }


        logger.info("--> starting relocations...");
        int nodeShiftBased = numberOfReplicas; // if we have replicas shift those
        for (int i = 0; i < numberOfRelocations; i++) {
            int fromNode = (i % 2);
            int toNode = fromNode == 0 ? 1 : 0;
            fromNode += nodeShiftBased;
            toNode += nodeShiftBased;

            List<IndexRequestBuilder> builders1 = new ArrayList<>();
            for (int numDocs = randomIntBetween(10, 30); numDocs > 0; numDocs--) {
                builders1.add(client().prepareIndex("test", "type").setSource("{}"));
            }

            List<IndexRequestBuilder> builders2 = new ArrayList<>();
            for (int numDocs = randomIntBetween(10, 30); numDocs > 0; numDocs--) {
                builders2.add(client().prepareIndex("test", "type").setSource("{}"));
            }

            logger.info("--> START relocate the shard from {} to {}", nodes[fromNode], nodes[toNode]);


            client().admin().cluster().prepareReroute()
                    .add(new MoveAllocationCommand("test", 0, nodes[fromNode], nodes[toNode]))
                    .get();


            logger.debug("--> index [{}] documents", builders1.size());
            indexRandom(false, true, builders1);
            // wait for shard to reach post recovery
            postRecoveryShards.acquire(1);

            logger.debug("--> index [{}] documents", builders2.size());
            indexRandom(true, true, builders2);

            // verify cluster was finished.
            assertFalse(client().admin().cluster().prepareHealth().setWaitForNoRelocatingShards(true).setWaitForEvents(Priority.LANGUID).setTimeout("30s").get().isTimedOut());
            logger.info("--> DONE relocate the shard from {} to {}", fromNode, toNode);

            logger.debug("--> verifying all searches return the same number of docs");
            long expectedCount = -1;
            for (Client client : clients()) {
                SearchResponse response = client.prepareSearch("test").setPreference("_local").setSize(0).get();
                assertNoFailures(response);
                if (expectedCount < 0) {
                    expectedCount = response.getHits().totalHits();
                } else {
                    assertEquals(expectedCount, response.getHits().totalHits());
                }
            }

        }
    }

    public void testCancellationCleansTempFiles() throws Exception {
        final String indexName = "test";

        final String p_node = internalCluster().startNode();

        prepareCreate(indexName, Settings.builder()
            .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 1, IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 0)
        ).get();

        internalCluster().startNodesAsync(2).get();

        List<IndexRequestBuilder> requests = new ArrayList<>();
        int numDocs = scaledRandomIntBetween(25, 250);
        for (int i = 0; i < numDocs; i++) {
            requests.add(client().prepareIndex(indexName, "type").setSource("{}"));
        }
        indexRandom(true, requests);
        assertFalse(client().admin().cluster().prepareHealth().setWaitForNodes("3").setWaitForGreenStatus().get().isTimedOut());
        flush();

        int allowedFailures = randomIntBetween(3, 10);
        logger.info("--> blocking recoveries from primary (allowed failures: [{}])", allowedFailures);
        CountDownLatch corruptionCount = new CountDownLatch(allowedFailures);
        ClusterService clusterService = internalCluster().getInstance(ClusterService.class, p_node);
        MockTransportService mockTransportService = (MockTransportService) internalCluster().getInstance(TransportService.class, p_node);
        for (DiscoveryNode node : clusterService.state().nodes()) {
            if (!node.equals(clusterService.localNode())) {
                mockTransportService.addDelegate(internalCluster().getInstance(TransportService.class, node.getName()), new RecoveryCorruption(mockTransportService.original(), corruptionCount));
            }
        }

        client().admin().indices().prepareUpdateSettings(indexName).setSettings(Settings.builder().put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)).get();

        corruptionCount.await();

        logger.info("--> stopping replica assignment");
        assertAcked(client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(Settings.builder().put(EnableAllocationDecider.CLUSTER_ROUTING_ALLOCATION_ENABLE_SETTING.getKey(), "none")));

        logger.info("--> wait for all replica shards to be removed, on all nodes");
        assertBusy(() -> {
            for (String node : internalCluster().getNodeNames()) {
                if (node.equals(p_node)) {
                    continue;
                }
                ClusterState state = client(node).admin().cluster().prepareState().setLocal(true).get().getState();
                assertThat(node + " indicates assigned replicas",
                        state.getRoutingTable().index(indexName).shardsWithState(ShardRoutingState.UNASSIGNED).size(), equalTo(1));
            }
        });

        logger.info("--> verifying no temporary recoveries are left");
        for (String node : internalCluster().getNodeNames()) {
            NodeEnvironment nodeEnvironment = internalCluster().getInstance(NodeEnvironment.class, node);
            for (final Path shardLoc : nodeEnvironment.availableShardPaths(new ShardId(indexName, "_na_", 0))) {
                if (Files.exists(shardLoc)) {
                    assertBusy(() -> {
                        try {
                            Files.walkFileTree(shardLoc, new SimpleFileVisitor<Path>() {
                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                    assertThat("found a temporary recovery file: " + file, file.getFileName().toString(), not(startsWith("recovery.")));
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        } catch (IOException e) {
                            throw new AssertionError("failed to walk file tree starting at [" + shardLoc + "]", e);
                        }
                    });
                }
            }
        }
    }

    public void testIndexAndRelocateConcurrently() throws ExecutionException, InterruptedException {
        int halfNodes = randomIntBetween(1, 3);
        Settings blueSetting = Settings.builder().put("node.attr.color", "blue").build();
        InternalTestCluster.Async<List<String>> blueFuture = internalCluster().startNodesAsync(halfNodes, blueSetting);
        Settings redSetting = Settings.builder().put("node.attr.color", "red").build();
        InternalTestCluster.Async<java.util.List<String>> redFuture = internalCluster().startNodesAsync(halfNodes, redSetting);
        blueFuture.get();
        redFuture.get();
        logger.info("blue nodes: {}", blueFuture.get());
        logger.info("red nodes: {}", redFuture.get());
        ensureStableCluster(halfNodes * 2);

        assertAcked(prepareCreate("test", Settings.builder()
            .put("index.routing.allocation.exclude.color", "blue")
            .put(indexSettings())
            .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, randomInt(halfNodes - 1))
        ));
        assertAllShardsOnNodes("test", redFuture.get().toArray(new String[2]));
        int numDocs = randomIntBetween(100, 150);
        ArrayList<String> ids = new ArrayList<>();
        logger.info(" --> indexing [{}] docs", numDocs);
        IndexRequestBuilder[] docs = new IndexRequestBuilder[numDocs];
        for (int i = 0; i < numDocs; i++) {
            String id = randomRealisticUnicodeOfLength(10) + String.valueOf(i);
            ids.add(id);
            docs[i] = client().prepareIndex("test", "type1", id).setSource("field1", English.intToEnglish(i));
        }
        indexRandom(true, docs);
        SearchResponse countResponse = client().prepareSearch("test").get();
        assertHitCount(countResponse, numDocs);

        logger.info(" --> moving index to new nodes");
        Settings build = Settings.builder().put("index.routing.allocation.exclude.color", "red")
            .put("index.routing.allocation.include.color", "blue").build();
        client().admin().indices().prepareUpdateSettings("test").setSettings(build).execute().actionGet();

        // index while relocating
        logger.info(" --> indexing [{}] more docs", numDocs);
        for (int i = 0; i < numDocs; i++) {
            String id = randomRealisticUnicodeOfLength(10) + String.valueOf(numDocs + i);
            ids.add(id);
            docs[i] = client().prepareIndex("test", "type1", id).setSource("field1", English.intToEnglish(numDocs + i));
        }
        indexRandom(true, docs);
        numDocs *= 2;

        logger.info(" --> waiting for relocation to complete");
        ensureGreen("test"); // move all shards to the new nodes (it waits on relocation)

        final int numIters = randomIntBetween(10, 20);
        for (int i = 0; i < numIters; i++) {
            logger.info(" --> checking iteration {}", i);
            SearchResponse afterRelocation = client().prepareSearch().setSize(ids.size()).get();
            assertNoFailures(afterRelocation);
            assertSearchHits(afterRelocation, ids.toArray(new String[ids.size()]));
        }
    }

    class RecoveryCorruption extends MockTransportService.DelegateTransport {

        private final CountDownLatch corruptionCount;

        public RecoveryCorruption(Transport transport, CountDownLatch corruptionCount) {
            super(transport);
            this.corruptionCount = corruptionCount;
        }

        @Override
        public void sendRequest(DiscoveryNode node, long requestId, String action, TransportRequest request, TransportRequestOptions options) throws IOException, TransportException {
            if (action.equals(PeerRecoveryTargetService.Actions.FILE_CHUNK)) {
                RecoveryFileChunkRequest chunkRequest = (RecoveryFileChunkRequest) request;
                if (chunkRequest.name().startsWith(IndexFileNames.SEGMENTS)) {
                    // corrupting the segments_N files in order to make sure future recovery re-send files
                    logger.debug("corrupting [{}] to {}. file name: [{}]", action, node, chunkRequest.name());
                    assert chunkRequest.content().toBytesRef().bytes == chunkRequest.content().toBytesRef().bytes : "no internal reference!!";
                    byte[] array = chunkRequest.content().toBytesRef().bytes;
                    array[0] = (byte) ~array[0]; // flip one byte in the content
                    corruptionCount.countDown();
                }
                transport.sendRequest(node, requestId, action, request, options);
            } else {
                transport.sendRequest(node, requestId, action, request, options);
            }
        }
    }
}
