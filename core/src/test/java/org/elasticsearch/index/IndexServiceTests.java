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

package org.elasticsearch.index;

import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.TopDocs;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.engine.Engine;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.translog.Translog;
import org.elasticsearch.indices.InvalidAliasNameException;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.elasticsearch.threadpool.ThreadPool;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.index.query.QueryBuilders.termQuery;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** Unit test(s) for IndexService */
public class IndexServiceTests extends ESSingleNodeTestCase {
    public void testDetermineShadowEngineShouldBeUsed() {
        Settings regularSettings = Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 2)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .build();

        Settings shadowSettings = Settings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, 2)
                .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1)
                .put(IndexMetaData.SETTING_SHADOW_REPLICAS, true)
                .build();

        assertFalse("no shadow replicas for normal settings", IndexService.useShadowEngine(true, regularSettings));
        assertFalse("no shadow replicas for normal settings", IndexService.useShadowEngine(false, regularSettings));
        assertFalse("no shadow replicas for primary shard with shadow settings", IndexService.useShadowEngine(true, shadowSettings));
        assertTrue("shadow replicas for replica shards with shadow settings",IndexService.useShadowEngine(false, shadowSettings));
    }

    public IndexService newIndexService() {
        Settings settings = Settings.builder().put("name", "indexServiceTests").build();
        return createIndex("test", settings);
    }


    public static CompressedXContent filter(QueryBuilder filterBuilder) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder();
        filterBuilder.toXContent(builder, ToXContent.EMPTY_PARAMS);
        builder.close();
        return new CompressedXContent(builder.string());
    }

    public void testFilteringAliases() throws Exception {
        IndexService indexService = newIndexService();
        IndexShard shard = indexService.getShard(0);
        add(indexService, "cats", filter(termQuery("animal", "cat")));
        add(indexService, "dogs", filter(termQuery("animal", "dog")));
        add(indexService, "all", null);

        assertThat(indexService.getMetaData().getAliases().containsKey("cats"), equalTo(true));
        assertThat(indexService.getMetaData().getAliases().containsKey("dogs"), equalTo(true));
        assertThat(indexService.getMetaData().getAliases().containsKey("turtles"), equalTo(false));

        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "cats").toString(), equalTo("animal:cat"));
        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "cats", "dogs").toString(), equalTo("animal:cat animal:dog"));

        // Non-filtering alias should turn off all filters because filters are ORed
        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "all"), nullValue());
        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "cats", "all"), nullValue());
        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "all", "cats"), nullValue());

        add(indexService, "cats", filter(termQuery("animal", "feline")));
        add(indexService, "dogs", filter(termQuery("animal", "canine")));
        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "dogs", "cats").toString(), equalTo("animal:canine animal:feline"));
    }

    public void testAliasFilters() throws Exception {
        IndexService indexService = newIndexService();
        IndexShard shard = indexService.getShard(0);

        add(indexService, "cats", filter(termQuery("animal", "cat")));
        add(indexService, "dogs", filter(termQuery("animal", "dog")));

        assertThat(indexService.aliasFilter(shard.getQueryShardContext()), nullValue());
        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "dogs").toString(), equalTo("animal:dog"));
        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "dogs", "cats").toString(), equalTo("animal:dog animal:cat"));

        add(indexService, "cats", filter(termQuery("animal", "feline")));
        add(indexService, "dogs", filter(termQuery("animal", "canine")));

        assertThat(indexService.aliasFilter(shard.getQueryShardContext(), "dogs", "cats").toString(), equalTo("animal:canine animal:feline"));
    }

    public void testRemovedAliasFilter() throws Exception {
        IndexService indexService = newIndexService();
        IndexShard shard = indexService.getShard(0);

        add(indexService, "cats", filter(termQuery("animal", "cat")));
        remove(indexService, "cats");
        try {
            indexService.aliasFilter(shard.getQueryShardContext(), "cats");
            fail("Expected InvalidAliasNameException");
        } catch (InvalidAliasNameException e) {
            assertThat(e.getMessage(), containsString("Invalid alias name [cats]"));
        }
    }

    public void testUnknownAliasFilter() throws Exception {
        IndexService indexService = newIndexService();
        IndexShard shard = indexService.getShard(0);

        add(indexService, "cats", filter(termQuery("animal", "cat")));
        add(indexService, "dogs", filter(termQuery("animal", "dog")));

        try {
            indexService.aliasFilter(shard.getQueryShardContext(), "unknown");
            fail();
        } catch (InvalidAliasNameException e) {
            // all is well
        }
    }

    private void remove(IndexService service, String alias) {
        IndexMetaData build = IndexMetaData.builder(service.getMetaData()).removeAlias(alias).build();
        service.updateMetaData(build);
    }

    private void add(IndexService service, String alias, @Nullable CompressedXContent filter) {
        IndexMetaData build = IndexMetaData.builder(service.getMetaData()).putAlias(AliasMetaData.builder(alias).filter(filter).build()).build();
        service.updateMetaData(build);
    }

    public void testBaseAsyncTask() throws InterruptedException, IOException {
        IndexService indexService = newIndexService();
        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> latch2 = new AtomicReference<>(new CountDownLatch(1));
        final AtomicInteger count = new AtomicInteger();
        IndexService.BaseAsyncTask task = new IndexService.BaseAsyncTask(indexService, TimeValue.timeValueMillis(1)) {
            @Override
            protected void runInternal() {
                count.incrementAndGet();
                assertTrue("generic threadpool is configured", Thread.currentThread().getName().contains("[generic]"));
                latch.get().countDown();
                try {
                    latch2.get().await();
                } catch (InterruptedException e) {
                    fail("interrupted");
                }
                if (randomBoolean()) { // task can throw exceptions!!
                    if (randomBoolean()) {
                        throw new RuntimeException("foo");
                    } else {
                        throw new RuntimeException("bar");
                    }
                }
            }

            @Override
            protected String getThreadPool() {
                return ThreadPool.Names.GENERIC;
            }
        };
        latch.get().await();
        latch.set(new CountDownLatch(1));
        assertEquals(1, count.get());
        latch2.get().countDown();
        latch2.set(new CountDownLatch(1));

        latch.get().await();
        assertEquals(2, count.get());
        task.close();
        latch2.get().countDown();
        assertEquals(2, count.get());


        task = new IndexService.BaseAsyncTask(indexService, TimeValue.timeValueMillis(1000000)) {
            @Override
            protected void runInternal() {

            }
        };
        assertTrue(task.mustReschedule());
        indexService.close("simon says", false);
        assertFalse("no shards left", task.mustReschedule());
        assertTrue(task.isScheduled());
        task.close();
        assertFalse(task.isScheduled());
    }

    public void testRefreshTaskIsUpdated() throws IOException {
        IndexService indexService = newIndexService();
        IndexService.AsyncRefreshTask refreshTask = indexService.getRefreshTask();
        assertEquals(1000, refreshTask.getInterval().millis());
        assertTrue(indexService.getRefreshTask().mustReschedule());

        // now disable
        IndexMetaData metaData = IndexMetaData.builder(indexService.getMetaData()).settings(Settings.builder().put(indexService.getMetaData().getSettings()).put(IndexSettings.INDEX_REFRESH_INTERVAL, -1)).build();
        indexService.updateMetaData(metaData);
        assertNotSame(refreshTask, indexService.getRefreshTask());
        assertTrue(refreshTask.isClosed());
        assertFalse(refreshTask.isScheduled());
        assertFalse(indexService.getRefreshTask().mustReschedule());

        // set it to 100ms
        metaData = IndexMetaData.builder(indexService.getMetaData()).settings(Settings.builder().put(indexService.getMetaData().getSettings()).put(IndexSettings.INDEX_REFRESH_INTERVAL, "100ms")).build();
        indexService.updateMetaData(metaData);
        assertNotSame(refreshTask, indexService.getRefreshTask());
        assertTrue(refreshTask.isClosed());

        refreshTask = indexService.getRefreshTask();
        assertTrue(refreshTask.mustReschedule());
        assertTrue(refreshTask.isScheduled());
        assertEquals(100, refreshTask.getInterval().millis());

        // set it to 200ms
        metaData = IndexMetaData.builder(indexService.getMetaData()).settings(Settings.builder().put(indexService.getMetaData().getSettings()).put(IndexSettings.INDEX_REFRESH_INTERVAL, "200ms")).build();
        indexService.updateMetaData(metaData);
        assertNotSame(refreshTask, indexService.getRefreshTask());
        assertTrue(refreshTask.isClosed());

        refreshTask = indexService.getRefreshTask();
        assertTrue(refreshTask.mustReschedule());
        assertTrue(refreshTask.isScheduled());
        assertEquals(200, refreshTask.getInterval().millis());

        // set it to 200ms again
        metaData = IndexMetaData.builder(indexService.getMetaData()).settings(Settings.builder().put(indexService.getMetaData().getSettings()).put(IndexSettings.INDEX_REFRESH_INTERVAL, "200ms")).build();
        indexService.updateMetaData(metaData);
        assertSame(refreshTask, indexService.getRefreshTask());
        assertTrue(indexService.getRefreshTask().mustReschedule());
        assertTrue(refreshTask.isScheduled());
        assertFalse(refreshTask.isClosed());
        assertEquals(200, refreshTask.getInterval().millis());
        indexService.close("simon says", false);
        assertFalse(refreshTask.isScheduled());
        assertTrue(refreshTask.isClosed());
    }

    public void testFsyncTaskIsRunning() throws IOException {
        IndexService indexService = newIndexService();
        IndexService.AsyncTranslogFSync fsyncTask = indexService.getFsyncTask();
        assertNotNull(fsyncTask);
        assertEquals(5000, fsyncTask.getInterval().millis());
        assertTrue(fsyncTask.mustReschedule());
        assertTrue(fsyncTask.isScheduled());

        indexService.close("simon says", false);
        assertFalse(fsyncTask.isScheduled());
        assertTrue(fsyncTask.isClosed());
    }

    public void testRefreshActuallyWorks() throws Exception {
        IndexService indexService = newIndexService();
        ensureGreen("test");
        IndexService.AsyncRefreshTask refreshTask = indexService.getRefreshTask();
        assertEquals(1000, refreshTask.getInterval().millis());
        assertTrue(indexService.getRefreshTask().mustReschedule());

        // now disable
        IndexMetaData metaData = IndexMetaData.builder(indexService.getMetaData()).settings(Settings.builder().put(indexService.getMetaData().getSettings()).put(IndexSettings.INDEX_REFRESH_INTERVAL, -1)).build();
        indexService.updateMetaData(metaData);
        client().prepareIndex("test", "test", "1").setSource("{\"foo\": \"bar\"}").get();
        IndexShard shard = indexService.getShard(0);
        try (Engine.Searcher searcher = shard.acquireSearcher("test")) {
            TopDocs search = searcher.searcher().search(new MatchAllDocsQuery(), 10);
            assertEquals(0, search.totalHits);
        }
        // refresh every millisecond
        metaData = IndexMetaData.builder(indexService.getMetaData()).settings(Settings.builder().put(indexService.getMetaData().getSettings()).put(IndexSettings.INDEX_REFRESH_INTERVAL, "1ms")).build();
        indexService.updateMetaData(metaData);
        assertBusy(() -> {
            try (Engine.Searcher searcher = shard.acquireSearcher("test")) {
                TopDocs search = searcher.searcher().search(new MatchAllDocsQuery(), 10);
                assertEquals(1, search.totalHits);
            } catch (IOException e) {
                fail(e.getMessage());
            }
        });
    }

    public void testAsyncFsyncActuallyWorks() throws Exception {
        Settings settings = Settings.builder()
            .put(IndexSettings.INDEX_TRANSLOG_SYNC_INTERVAL, "10ms") // very often :)
            .put(IndexSettings.INDEX_TRANSLOG_DURABILITY, Translog.Durability.ASYNC)
            .build();
        IndexService indexService = createIndex("test", settings);
        ensureGreen("test");
        assertTrue(indexService.getRefreshTask().mustReschedule());
        client().prepareIndex("test", "test", "1").setSource("{\"foo\": \"bar\"}").get();
        IndexShard shard = indexService.getShard(0);
        assertBusy(() -> {
            assertFalse(shard.getTranslog().syncNeeded());
        });

    }
}
