/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.upgrade;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportResponse;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.upgrade.actions.IndexUpgradeAction;
import org.elasticsearch.xpack.upgrade.actions.IndexUpgradeInfoAction;
import org.elasticsearch.xpack.upgrade.actions.IndexUpgradeInfoAction.Response;
import org.elasticsearch.xpack.watcher.support.WatcherIndexTemplateRegistry;
import org.junit.Before;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertThrows;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.core.IsEqual.equalTo;

public class IndexUpgradeIT extends IndexUpgradeIntegTestCase {

    @Before
    public void resetLicensing() throws Exception {
        enableLicensing();
    }

    public void testIndexUpgradeInfo() {
        // Testing only negative case here, the positive test is done in bwcTests
        assertAcked(client().admin().indices().prepareCreate("test").get());
        ensureYellow("test");
        Response response = client().prepareExecute(IndexUpgradeInfoAction.INSTANCE).setIndices("test").get();
        assertThat(response.getActions().entrySet(), empty());
    }

    public void testIndexUpgradeInfoLicense() throws Exception {
        assertAcked(client().admin().indices().prepareCreate("test").get());
        ensureYellow("test");
        disableLicensing();
        ElasticsearchSecurityException e = expectThrows(ElasticsearchSecurityException.class,
                () -> client().prepareExecute(IndexUpgradeInfoAction.INSTANCE).setIndices("test").get());
        assertThat(e.getMessage(), equalTo("current license is non-compliant for [upgrade]"));
        enableLicensing();
        Response response = client().prepareExecute(IndexUpgradeInfoAction.INSTANCE).setIndices("test").get();
        assertThat(response.getActions().entrySet(), empty());
    }

    public void testUpToDateIndexUpgrade() throws Exception {
        // Testing only negative case here, the positive test is done in bwcTests
        String testIndex = "test";
        String testType = "doc";
        assertAcked(client().admin().indices().prepareCreate(testIndex).get());
        indexRandom(true,
                client().prepareIndex(testIndex, testType, "1").setSource("{\"foo\":\"bar\"}", XContentType.JSON),
                client().prepareIndex(testIndex, testType, "2").setSource("{\"foo\":\"baz\"}", XContentType.JSON)
        );
        ensureYellow(testIndex);

        IllegalStateException ex = expectThrows(IllegalStateException.class,
                () -> client().prepareExecute(IndexUpgradeAction.INSTANCE).setIndex(testIndex).get());
        assertThat(ex.getMessage(), equalTo("Index [" + testIndex + "] cannot be upgraded"));

        SearchResponse searchResponse = client().prepareSearch(testIndex).get();
        assertEquals(2L, searchResponse.getHits().getTotalHits());
    }

    public void testInternalUpgradePrePostChecks() throws Exception {
        String testIndex = "internal_index";
        String testType = "test";
        Long val = randomLong();
        AtomicBoolean preUpgradeIsCalled = new AtomicBoolean();
        AtomicBoolean postUpgradeIsCalled = new AtomicBoolean();

        IndexUpgradeCheck check = new IndexUpgradeCheck<Long>(
                "test", Settings.EMPTY,
                indexMetaData -> {
                    if (indexMetaData.getIndex().getName().equals(testIndex)) {
                        return UpgradeActionRequired.UPGRADE;
                    } else {
                        return UpgradeActionRequired.NOT_APPLICABLE;
                    }
                },
                client(), internalCluster().clusterService(internalCluster().getMasterName()), Strings.EMPTY_ARRAY, null,
                listener -> {
                    assertFalse(preUpgradeIsCalled.getAndSet(true));
                    assertFalse(postUpgradeIsCalled.get());
                    listener.onResponse(val);
                },
                (aLong, listener) -> {
                    assertTrue(preUpgradeIsCalled.get());
                    assertFalse(postUpgradeIsCalled.getAndSet(true));
                    assertEquals(aLong, val);
                    listener.onResponse(TransportResponse.Empty.INSTANCE);
                });

        assertAcked(client().admin().indices().prepareCreate(testIndex).get());
        indexRandom(true,
                client().prepareIndex(testIndex, testType, "1").setSource("{\"foo\":\"bar\"}", XContentType.JSON),
                client().prepareIndex(testIndex, testType, "2").setSource("{\"foo\":\"baz\"}", XContentType.JSON)
        );
        ensureYellow(testIndex);

        IndexUpgradeService service = new IndexUpgradeService(Settings.EMPTY, Collections.singletonList(check));

        PlainActionFuture<BulkByScrollResponse> future = PlainActionFuture.newFuture();
        service.upgrade(new TaskId("abc", 123), testIndex, clusterService().state(), future);
        BulkByScrollResponse response = future.actionGet();
        assertThat(response.getCreated(), equalTo(2L));

        SearchResponse searchResponse = client().prepareSearch(testIndex).get();
        assertEquals(2L, searchResponse.getHits().getTotalHits());

        assertTrue(preUpgradeIsCalled.get());
        assertTrue(postUpgradeIsCalled.get());
    }

    public void testIndexUpgradeInfoOnEmptyCluster() {
        // On empty cluster asking for all indices shouldn't fail since no indices means nothing needs to be upgraded
        Response response = client().prepareExecute(IndexUpgradeInfoAction.INSTANCE).setIndices("_all").get();
        assertThat(response.getActions().entrySet(), empty());

        // but calling on a particular index should fail
        assertThrows(client().prepareExecute(IndexUpgradeInfoAction.INSTANCE).setIndices("test"), IndexNotFoundException.class);
    }

    public void testPreWatchesUpgrade() throws Exception {
        Settings templateSettings = Settings.builder().put("index.number_of_shards", 2).build();

        // create legacy watches template
        if (randomBoolean()) {
            assertAcked(client().admin().indices().preparePutTemplate("watches")
                    .setSettings(templateSettings).setTemplate(".watches*")
                    .get());
        }

        // create old watch history template
        if (randomBoolean()) {
            assertAcked(client().admin().indices().preparePutTemplate("watch_history_foo")
                    .setSettings(templateSettings).setTemplate("watch_history-*")
                    .get());
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> exception = new AtomicReference<>();
        ActionListener<Boolean> listener = ActionListener.wrap(
                r -> latch.countDown(),
                e -> {
                    latch.countDown();
                    exception.set(e);
                });

        // use the internal client from the master, instead of client(), so we dont have to deal with remote transport exceptions
        // and it works like the real implementation
        InternalClient client = internalCluster().getInstance(InternalClient.class, internalCluster().getMasterName());
        Upgrade.preWatchesIndexUpgrade(client, listener, false);

        assertThat("Latch was not counted down", latch.await(10, TimeUnit.SECONDS), is(true));
        assertThat(exception.get(), is(nullValue()));

        // ensure old index templates are gone, new ones are created
        List<String> templateNames = getTemplateNames();
        assertThat(templateNames, not(hasItem(startsWith("watch_history"))));
        assertThat(templateNames, not(hasItem("watches")));
        assertThat(templateNames, hasItem(".watches"));

        // last let's be sure that the watcher index template registry does not add back any template by accident with the current state
        Settings settings = internalCluster().getInstance(Settings.class, internalCluster().getMasterName());
        ClusterService clusterService = internalCluster().getInstance(ClusterService.class, internalCluster().getMasterName());
        ThreadPool threadPool = internalCluster().getInstance(ThreadPool.class, internalCluster().getMasterName());
        WatcherIndexTemplateRegistry registry =
                new WatcherIndexTemplateRegistry(settings, clusterService, threadPool, client);

        ClusterState state = clusterService.state();
        ClusterChangedEvent event = new ClusterChangedEvent("whatever", state, state);
        registry.clusterChanged(event);

        List<String> templateNamesAfterClusterChangedEvent = getTemplateNames();
        assertThat(templateNamesAfterClusterChangedEvent, not(hasItem(startsWith("watch_history"))));
        assertThat(templateNamesAfterClusterChangedEvent, not(hasItem("watches")));
        assertThat(templateNamesAfterClusterChangedEvent, hasItem(".watches"));
    }

    public void testPreTriggeredWatchesUpgrade() throws Exception {
        Settings templateSettings = Settings.builder().put("index.number_of_shards", 2).build();
        // create legacy triggered watch template
        if (randomBoolean()) {
            assertAcked(client().admin().indices().preparePutTemplate("triggered_watches")
                    .setSettings(templateSettings).setTemplate(".triggered_watches*")
                    .get());
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Exception> exception = new AtomicReference<>();
        ActionListener<Boolean> listener = ActionListener.wrap(
                r -> latch.countDown(),
                e -> {
                    latch.countDown();
                    exception.set(e);
                });

        // use the internal client from the master, instead of client(), so we dont have to deal with remote transport exceptions
        // and it works like the real implementation
        InternalClient client = internalCluster().getInstance(InternalClient.class, internalCluster().getMasterName());
        Upgrade.preTriggeredWatchesIndexUpgrade(client, listener, false);

        assertThat("Latch was not counted down", latch.await(10, TimeUnit.SECONDS), is(true));
        assertThat(exception.get(), is(nullValue()));

        // ensure old index templates are gone, new ones are created
        List<String> templateNames = getTemplateNames();
        assertThat(templateNames, not(hasItem("triggered_watches")));
        assertThat(templateNames, hasItem(".triggered_watches"));

        // last let's be sure that the watcher index template registry does not add back any template by accident with the current state
        Settings settings = internalCluster().getInstance(Settings.class, internalCluster().getMasterName());
        ClusterService clusterService = internalCluster().getInstance(ClusterService.class, internalCluster().getMasterName());
        ThreadPool threadPool = internalCluster().getInstance(ThreadPool.class, internalCluster().getMasterName());
        WatcherIndexTemplateRegistry registry =
                new WatcherIndexTemplateRegistry(settings, clusterService, threadPool, client);

        ClusterState state = clusterService.state();
        ClusterChangedEvent event = new ClusterChangedEvent("whatever", state, state);
        registry.clusterChanged(event);
        List<String> templateNamesAfterClusterChangedEvent = getTemplateNames();
        assertThat(templateNamesAfterClusterChangedEvent, not(hasItem("triggered_watches")));
        assertThat(templateNamesAfterClusterChangedEvent, hasItem(".triggered_watches"));
    }

    private List<String> getTemplateNames() {
        GetIndexTemplatesResponse templatesResponse = client().admin().indices().prepareGetTemplates().get();
        return templatesResponse.getIndexTemplates().stream()
                .map(IndexTemplateMetaData::getName)
                .collect(Collectors.toList());
    }
}
