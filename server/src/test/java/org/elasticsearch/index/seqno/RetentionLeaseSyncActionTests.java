/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache license, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the license for the specific language governing permissions and
 * limitations under the license.
 */

package org.elasticsearch.index.seqno;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.store.AlreadyClosedException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.ActionTestUtils;
import org.elasticsearch.action.support.replication.TransportWriteAction;
import org.elasticsearch.cluster.action.shard.ShardStateAction;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.internal.io.IOUtils;
import org.elasticsearch.gateway.WriteStateException;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.IndexShardClosedException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.CapturingTransport;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.mock.orig.Mockito.verifyNoMoreInteractions;
import static org.elasticsearch.mock.orig.Mockito.when;
import static org.elasticsearch.test.ClusterServiceUtils.createClusterService;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Matchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class RetentionLeaseSyncActionTests extends ESTestCase {

    private ThreadPool threadPool;
    private CapturingTransport transport;
    private ClusterService clusterService;
    private TransportService transportService;
    private ShardStateAction shardStateAction;

    public void setUp() throws Exception {
        super.setUp();
        threadPool = new TestThreadPool(getClass().getName());
        transport = new CapturingTransport();
        clusterService = createClusterService(threadPool);
        transportService = transport.createTransportService(
                clusterService.getSettings(),
                threadPool,
                TransportService.NOOP_TRANSPORT_INTERCEPTOR,
                boundAddress -> clusterService.localNode(),
                null,
                Collections.emptySet());
        transportService.start();
        transportService.acceptIncomingRequests();
        shardStateAction = new ShardStateAction(clusterService, transportService, null, null, threadPool);
    }

    public void tearDown() throws Exception {
        try {
            IOUtils.close(transportService, clusterService, transport);
        } finally {
            terminate(threadPool);
        }
        super.tearDown();
    }

    public void testRetentionLeaseSyncActionOnPrimary() {
        final IndicesService indicesService = mock(IndicesService.class);

        final Index index = new Index("index", "uuid");
        final IndexService indexService = mock(IndexService.class);
        when(indicesService.indexServiceSafe(index)).thenReturn(indexService);

        final int id = randomIntBetween(0, 4);
        final IndexShard indexShard = mock(IndexShard.class);
        when(indexService.getShard(id)).thenReturn(indexShard);

        final ShardId shardId = new ShardId(index, id);
        when(indexShard.shardId()).thenReturn(shardId);

        final RetentionLeaseSyncAction action = new RetentionLeaseSyncAction(
                Settings.EMPTY,
                transportService,
                clusterService,
                indicesService,
                threadPool,
                shardStateAction,
                new ActionFilters(Collections.emptySet()));
        final RetentionLeases retentionLeases = mock(RetentionLeases.class);
        final RetentionLeaseSyncAction.Request request = new RetentionLeaseSyncAction.Request(indexShard.shardId(), retentionLeases);
        action.shardOperationOnPrimary(request, indexShard,
            ActionTestUtils.assertNoFailureListener(result -> {
                    // the retention leases on the shard should be persisted
                    verify(indexShard).persistRetentionLeases();
                    // we should forward the request containing the current retention leases to the replica
                    assertThat(result.replicaRequest(), sameInstance(request));
                    // we should start with an empty replication response
                    assertNull(result.finalResponseIfSuccessful.getShardInfo());
                }
            ));
    }

    public void testRetentionLeaseSyncActionOnReplica() throws WriteStateException {
        final IndicesService indicesService = mock(IndicesService.class);

        final Index index = new Index("index", "uuid");
        final IndexService indexService = mock(IndexService.class);
        when(indicesService.indexServiceSafe(index)).thenReturn(indexService);

        final int id = randomIntBetween(0, 4);
        final IndexShard indexShard = mock(IndexShard.class);
        when(indexService.getShard(id)).thenReturn(indexShard);

        final ShardId shardId = new ShardId(index, id);
        when(indexShard.shardId()).thenReturn(shardId);

        final RetentionLeaseSyncAction action = new RetentionLeaseSyncAction(
                Settings.EMPTY,
                transportService,
                clusterService,
                indicesService,
                threadPool,
                shardStateAction,
                new ActionFilters(Collections.emptySet()));
        final RetentionLeases retentionLeases = mock(RetentionLeases.class);
        final RetentionLeaseSyncAction.Request request = new RetentionLeaseSyncAction.Request(indexShard.shardId(), retentionLeases);

        final TransportWriteAction.WriteReplicaResult<RetentionLeaseSyncAction.Request> result =
                action.shardOperationOnReplica(request, indexShard);
        // the retention leases on the shard should be updated
        verify(indexShard).updateRetentionLeasesOnReplica(retentionLeases);
        // the retention leases on the shard should be persisted
        verify(indexShard).persistRetentionLeases();
        // the result should indicate success
        final AtomicBoolean success = new AtomicBoolean();
        result.respond(ActionListener.wrap(r -> success.set(true), e -> fail(e.toString())));
        assertTrue(success.get());
    }

    public void testRetentionLeaseSyncExecution() {
        final IndicesService indicesService = mock(IndicesService.class);

        final Index index = new Index("index", "uuid");
        final IndexService indexService = mock(IndexService.class);
        when(indicesService.indexServiceSafe(index)).thenReturn(indexService);

        final int id = randomIntBetween(0, 4);
        final IndexShard indexShard = mock(IndexShard.class);
        when(indexService.getShard(id)).thenReturn(indexShard);

        final ShardId shardId = new ShardId(index, id);
        when(indexShard.shardId()).thenReturn(shardId);

        final Logger retentionLeaseSyncActionLogger = mock(Logger.class);

        final RetentionLeases retentionLeases = mock(RetentionLeases.class);
        final AtomicBoolean invoked = new AtomicBoolean();
        final RetentionLeaseSyncAction action = new RetentionLeaseSyncAction(
                Settings.EMPTY,
                transportService,
                clusterService,
                indicesService,
                threadPool,
                shardStateAction,
                new ActionFilters(Collections.emptySet())) {

            @Override
            protected void doExecute(Task task, Request request, ActionListener<Response> listener) {
                assertTrue(threadPool.getThreadContext().isSystemContext());
                assertThat(request.shardId(), sameInstance(indexShard.shardId()));
                assertThat(request.getRetentionLeases(), sameInstance(retentionLeases));
                if (randomBoolean()) {
                    listener.onResponse(new Response());
                } else {
                    final Exception e = randomFrom(
                            new AlreadyClosedException("closed"),
                            new IndexShardClosedException(indexShard.shardId()),
                            new RuntimeException("failed"));
                    listener.onFailure(e);
                    if (e instanceof AlreadyClosedException == false && e instanceof IndexShardClosedException == false) {
                        final ArgumentCaptor<ParameterizedMessage> captor = ArgumentCaptor.forClass(ParameterizedMessage.class);
                        verify(retentionLeaseSyncActionLogger).warn(captor.capture(), same(e));
                        final ParameterizedMessage message = captor.getValue();
                        assertThat(message.getFormat(), equalTo("{} retention lease sync failed"));
                        assertThat(message.getParameters(), arrayContaining(indexShard.shardId()));
                    }
                    verifyNoMoreInteractions(retentionLeaseSyncActionLogger);
                }
                invoked.set(true);
            }

            @Override
            protected Logger getLogger() {
                return retentionLeaseSyncActionLogger;
            }
        };

        // execution happens on the test thread, so no need to register an actual listener to callback
        action.sync(indexShard.shardId(), retentionLeases, ActionListener.wrap(() -> {}));
        assertTrue(invoked.get());
    }

    public void testBlocks() {
        final IndicesService indicesService = mock(IndicesService.class);

        final Index index = new Index("index", "uuid");
        final IndexService indexService = mock(IndexService.class);
        when(indicesService.indexServiceSafe(index)).thenReturn(indexService);

        final int id = randomIntBetween(0, 4);
        final IndexShard indexShard = mock(IndexShard.class);
        when(indexService.getShard(id)).thenReturn(indexShard);

        final ShardId shardId = new ShardId(index, id);
        when(indexShard.shardId()).thenReturn(shardId);

        final RetentionLeaseSyncAction action = new RetentionLeaseSyncAction(
                Settings.EMPTY,
                transportService,
                clusterService,
                indicesService,
                threadPool,
                shardStateAction,
                new ActionFilters(Collections.emptySet()));

        assertNull(action.indexBlockLevel());
    }

}
