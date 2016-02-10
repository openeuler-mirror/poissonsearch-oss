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

package org.elasticsearch.plugin.reindex;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestBuilder;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.BackoffPolicy;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkItemResponse.Failure;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.ClearScrollRequest;
import org.elasticsearch.action.search.ClearScrollResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.action.support.replication.ReplicationRequest;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.FilterClient;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.internal.InternalSearchHit;
import org.elasticsearch.search.internal.InternalSearchHits;
import org.elasticsearch.search.internal.InternalSearchResponse;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.client.NoOpClient;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Collections.emptyMap;
import static org.apache.lucene.util.TestUtil.randomSimpleString;
import static org.elasticsearch.action.bulk.BackoffPolicy.constantBackoff;
import static org.elasticsearch.common.unit.TimeValue.timeValueMillis;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.emptyCollectionOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.iterableWithSize;

public class AsyncBulkByScrollActionTests extends ESTestCase {
    private MyMockClient client;
    private ThreadPool threadPool;
    private DummyAbstractBulkByScrollRequest mainRequest;
    private SearchRequest firstSearchRequest;
    private PlainActionFuture<BulkIndexByScrollResponse> listener;
    private String scrollId;
    private BulkByScrollTask task;

    @Before
    public void setupForTest() {
        client = new MyMockClient(new NoOpClient(getTestName()));
        threadPool = new ThreadPool(getTestName());
        mainRequest = new DummyAbstractBulkByScrollRequest();
        firstSearchRequest = null;
        listener = new PlainActionFuture<>();
        scrollId = null;
        task = new BulkByScrollTask(0, "test", "test", "test");
    }

    @After
    public void tearDownAndVerifyCommonStuff() {
        client.close();
        threadPool.shutdown();
    }

    /**
     * Generates a random scrollId and registers it so that when the test
     * finishes we check that it was cleared. Subsequent calls reregister a new
     * random scroll id so it is checked instead.
     */
    private String scrollId() {
        scrollId = randomSimpleString(random(), 1, 1000); // Empty string's get special behavior we don't want
        return scrollId;
    }

    public void testScrollResponseSetsTotal() {
        // Default is 0, meaning unstarted
        assertEquals(0, task.getStatus().getTotal());

        long total = randomIntBetween(0, Integer.MAX_VALUE);
        InternalSearchHits hits = new InternalSearchHits(null, total, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        new DummyAbstractAsyncBulkByScrollAction()
            .onScrollResponse(new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));
        assertEquals(total, task.getStatus().getTotal());
    }

    public void testEachScrollResponseIsABatch() {
        // Replace the generic thread pool with one that executes immediately so the batch is updated immediately
        threadPool.shutdown();
        threadPool = new ThreadPool(getTestName()) {
            @Override
            public Executor generic() {
                return new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        command.run();
                    }
                };
            }
        };
        int maxBatches = randomIntBetween(0, 100);
        for (int batches = 1; batches < maxBatches; batches++) {
            InternalSearchHit hit = new InternalSearchHit(0, "id", new Text("type"), emptyMap());
            InternalSearchHits hits = new InternalSearchHits(new InternalSearchHit[] { hit }, 0, 0);
            InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
            new DummyAbstractAsyncBulkByScrollAction()
                .onScrollResponse(new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));

            assertEquals(batches, task.getStatus().getBatches());
        }
    }

    public void testBulkResponseSetsLotsOfStatus() {
        mainRequest.setAbortOnVersionConflict(false);
        int maxBatches = randomIntBetween(0, 100);
        long versionConflicts = 0;
        long created = 0;
        long updated = 0;
        long deleted = 0;
        for (int batches = 0; batches < maxBatches; batches++) {
            BulkItemResponse[] responses = new BulkItemResponse[randomIntBetween(0, 100)];
            for (int i = 0; i < responses.length; i++) {
                ShardId shardId = new ShardId(new Index("name", "uid"), 0);
                String opType;
                if (rarely()) {
                    opType = randomSimpleString(random());
                    versionConflicts++;
                    responses[i] = new BulkItemResponse(i, opType, new Failure(shardId.getIndexName(), "type", "id" + i,
                            new VersionConflictEngineException(shardId, "type", "id", "test")));
                    continue;
                }
                boolean createdResponse;
                switch (randomIntBetween(0, 2)) {
                case 0:
                    opType = randomFrom("index", "create");
                    createdResponse = true;
                    created++;
                    break;
                case 1:
                    opType = randomFrom("index", "create");
                    createdResponse = false;
                    updated++;
                    break;
                case 2:
                    opType = "delete";
                    createdResponse = false;
                    deleted++;
                    break;
                default:
                    throw new RuntimeException("Bad scenario");
                }
                responses[i] = new BulkItemResponse(i, opType, new IndexResponse(shardId, "type", "id" + i, randomInt(), createdResponse));
            }
            new DummyAbstractAsyncBulkByScrollAction().onBulkResponse(new BulkResponse(responses, 0));
            assertEquals(versionConflicts, task.getStatus().getVersionConflicts());
            assertEquals(updated, task.getStatus().getUpdated());
            assertEquals(created, task.getStatus().getCreated());
            assertEquals(deleted, task.getStatus().getDeleted());
            assertEquals(versionConflicts, task.getStatus().getVersionConflicts());
        }
    }

    /**
     * Mimicks a ThreadPool rejecting execution of the task.
     */
    public void testThreadPoolRejectionsAbortRequest() throws Exception {
        threadPool.shutdown();
        threadPool = new ThreadPool(getTestName()) {
            @Override
            public Executor generic() {
                return new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        ((AbstractRunnable) command).onRejection(new EsRejectedExecutionException("test"));
                    }
                };
            }
        };
        InternalSearchHits hits = new InternalSearchHits(null, 0, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        new DummyAbstractAsyncBulkByScrollAction()
                .onScrollResponse(new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));
        try {
            listener.get();
            fail("Expected a failure");
        } catch (ExecutionException e) {
            assertThat(e.getMessage(), equalTo("EsRejectedExecutionException[test]"));
        }
        assertThat(client.scrollsCleared, contains(scrollId));
    }

    /**
     * Mimicks shard search failures usually caused by the data node serving the
     * scroll request going down.
     */
    public void testShardFailuresAbortRequest() throws Exception {
        ShardSearchFailure shardFailure = new ShardSearchFailure(new RuntimeException("test"));
        new DummyAbstractAsyncBulkByScrollAction()
                .onScrollResponse(new SearchResponse(null, scrollId(), 5, 4, randomLong(), new ShardSearchFailure[] { shardFailure }));
        BulkIndexByScrollResponse response = listener.get();
        assertThat(response.getIndexingFailures(), emptyCollectionOf(Failure.class));
        assertThat(response.getSearchFailures(), contains(shardFailure));
        assertThat(client.scrollsCleared, contains(scrollId));
    }

    /**
     * Mimicks bulk indexing failures.
     */
    public void testBulkFailuresAbortRequest() throws Exception {
        Failure failure = new Failure("index", "type", "id", new RuntimeException("test"));
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction();
        action.onBulkResponse(new BulkResponse(new BulkItemResponse[] {new BulkItemResponse(0, "index", failure)}, randomLong()));
        BulkIndexByScrollResponse response = listener.get();
        assertThat(response.getIndexingFailures(), contains(failure));
        assertThat(response.getSearchFailures(), emptyCollectionOf(ShardSearchFailure.class));
    }

    /**
     * Mimicks script failures or general wrongness by implementers.
     */
    public void testListenerReceiveBuildBulkExceptions() throws Exception {
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction() {
            @Override
            protected BulkRequest buildBulk(Iterable<SearchHit> docs) {
                throw new RuntimeException("surprise");
            }
        };
        InternalSearchHit hit = new InternalSearchHit(0, "id", new Text("type"), emptyMap());
        InternalSearchHits hits = new InternalSearchHits(new InternalSearchHit[] {hit}, 0, 0);
        InternalSearchResponse searchResponse = new InternalSearchResponse(hits, null, null, null, false, false);
        action.onScrollResponse(new SearchResponse(searchResponse, scrollId(), 5, 4, randomLong(), null));
        try {
            listener.get();
            fail("Expected failure.");
        } catch (ExecutionException e) {
            assertThat(e.getCause(), instanceOf(RuntimeException.class));
            assertThat(e.getCause().getMessage(), equalTo("surprise"));
        }
    }

    /**
     * Mimicks bulk rejections. These should be retried and eventually succeed.
     */
    public void testBulkRejectionsRetryWithEnoughRetries() throws Exception {
        int bulksToTry = randomIntBetween(1, 10);
        long retryAttempts = 0;
        for (int i = 0; i < bulksToTry; i++) {
            retryAttempts += retryTestCase(false);
            assertEquals(retryAttempts, task.getStatus().getRetries());
        }
    }

    /**
     * Mimicks bulk rejections. These should be retried but we fail anyway because we run out of retries.
     */
    public void testBulkRejectionsRetryAndFailAnyway() throws Exception {
        long retryAttempts = retryTestCase(true);
        assertEquals(retryAttempts, task.getStatus().getRetries());
    }

    private long retryTestCase(boolean failWithRejection) throws Exception {
        int totalFailures = randomIntBetween(1, mainRequest.getMaxRetries());
        int size = randomIntBetween(1, 100);
        int retryAttempts = totalFailures - (failWithRejection ? 1 : 0);

        client.bulksToReject = client.bulksAttempts.get() + totalFailures;
        /*
         * When we get a successful bulk response we usually start the next scroll request but lets just intercept that so we don't have to
         * deal with it. We just wait for it to happen.
         */
        CountDownLatch successLatch = new CountDownLatch(1);
        DummyAbstractAsyncBulkByScrollAction action = new DummyAbstractAsyncBulkByScrollAction() {
            @Override
            BackoffPolicy backoffPolicy() {
                // Force a backoff time of 0 to prevent sleeping
                return constantBackoff(timeValueMillis(0), retryAttempts);
            }

            @Override
            void startNextScrollRequest() {
                successLatch.countDown();
            }
        };
        BulkRequest request = new BulkRequest();
        for (int i = 0; i < size + 1; i++) {
            request.add(new IndexRequest("index", "type", "id" + i));
        }
        action.sendBulkRequest(request);
        if (failWithRejection) {
            BulkIndexByScrollResponse response = listener.get();
            assertThat(response.getIndexingFailures(), iterableWithSize(equalTo(1)));
            assertEquals(response.getIndexingFailures().get(0).getStatus(), RestStatus.TOO_MANY_REQUESTS);
            assertThat(response.getSearchFailures(), emptyCollectionOf(ShardSearchFailure.class));
        } else {
            successLatch.await(10, TimeUnit.SECONDS);
        }
        return retryAttempts;
    }

    /**
     * The default retry time matches what we say it is in the javadoc for the request.
     */
    public void testDefaultRetryTimes() {
        Iterator<TimeValue> policy = new DummyAbstractAsyncBulkByScrollAction().backoffPolicy().iterator();
        long millis = 0;
        while (policy.hasNext()) {
            millis += policy.next().millis();
        }
        /*
         * This is the total number of milliseconds that a reindex made with the default settings will backoff before attempting one final
         * time. If that request is rejected then the whole process fails with a rejected exception.
         */
        int defaultBackoffBeforeFailing = 24670;
        assertEquals(defaultBackoffBeforeFailing, millis);
    }

    private class DummyAbstractAsyncBulkByScrollAction
            extends AbstractAsyncBulkByScrollAction<DummyAbstractBulkByScrollRequest, BulkIndexByScrollResponse> {
        public DummyAbstractAsyncBulkByScrollAction() {
            super(AsyncBulkByScrollActionTests.this.task, logger, client, threadPool,
                    AsyncBulkByScrollActionTests.this.mainRequest, firstSearchRequest, listener);
        }

        @Override
        protected BulkRequest buildBulk(Iterable<SearchHit> docs) {
            return new BulkRequest();
        }

        @Override
        protected BulkIndexByScrollResponse buildResponse(TimeValue took, List<Failure> indexingFailures,
                List<ShardSearchFailure> searchFailures) {
            return new BulkIndexByScrollResponse(took, task.getStatus(), indexingFailures, searchFailures);
        }
    }

    private static class DummyAbstractBulkByScrollRequest extends AbstractBulkByScrollRequest<DummyAbstractBulkByScrollRequest> {
        @Override
        protected DummyAbstractBulkByScrollRequest self() {
            return this;
        }
    }

    private static class MyMockClient extends FilterClient {
        private final List<String> scrollsCleared = new ArrayList<>();
        private final AtomicInteger bulksAttempts = new AtomicInteger();

        private int bulksToReject = 0;

        public MyMockClient(Client in) {
            super(in);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected <Request extends ActionRequest<Request>, Response extends ActionResponse,
                RequestBuilder extends ActionRequestBuilder<Request, Response, RequestBuilder>> void doExecute(
                Action<Request, Response, RequestBuilder> action, Request request, ActionListener<Response> listener) {
            if (request instanceof ClearScrollRequest) {
                ClearScrollRequest clearScroll = (ClearScrollRequest) request;
                scrollsCleared.addAll(clearScroll.getScrollIds());
                listener.onResponse((Response) new ClearScrollResponse(true, clearScroll.getScrollIds().size()));
                return;
            }
            if (request instanceof BulkRequest) {
                BulkRequest bulk = (BulkRequest) request;
                int toReject;
                if (bulksAttempts.incrementAndGet() > bulksToReject) {
                    toReject = -1;
                } else {
                    toReject = randomIntBetween(0, bulk.requests().size() - 1);
                }
                BulkItemResponse[] responses = new BulkItemResponse[bulk.requests().size()];
                for (int i = 0; i < bulk.requests().size(); i++) {
                    ActionRequest<?> item = bulk.requests().get(i);
                    String opType;
                    DocWriteResponse response;
                    ShardId shardId = new ShardId(new Index(((ReplicationRequest<?>) item).index(), "uuid"), 0);
                    if (item instanceof IndexRequest) {
                        IndexRequest index = (IndexRequest) item;
                        opType = index.opType().lowercase();
                        response = new IndexResponse(shardId, index.type(), index.id(), randomIntBetween(0, Integer.MAX_VALUE),
                                true);
                    } else if (item instanceof UpdateRequest) {
                        UpdateRequest update = (UpdateRequest) item;
                        opType = "update";
                        response = new UpdateResponse(shardId, update.type(), update.id(),
                                randomIntBetween(0, Integer.MAX_VALUE), true);
                    } else if (item instanceof DeleteRequest) {
                        DeleteRequest delete = (DeleteRequest) item;
                        opType = "delete";
                        response = new DeleteResponse(shardId, delete.type(), delete.id(), randomIntBetween(0, Integer.MAX_VALUE),
                                true);
                    } else {
                        throw new RuntimeException("Unknown request:  " + item);
                    }
                    if (i == toReject) {
                        responses[i] = new BulkItemResponse(i, opType,
                                new Failure(response.getIndex(), response.getType(), response.getId(), new EsRejectedExecutionException()));
                    } else {
                        responses[i] = new BulkItemResponse(i, opType, response);
                    }
                }
                listener.onResponse((Response) new BulkResponse(responses, 1));
                return;
            }
            super.doExecute(action, request, listener);
        }
    }
}
