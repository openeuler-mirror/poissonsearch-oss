/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.collector.indices;

import org.elasticsearch.action.admin.indices.stats.IndexStats;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.marvel.agent.collector.AbstractCollectorTestCase;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.license.MarvelLicensee;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.hamcrest.Matchers.*;

@ClusterScope(numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public class IndexStatsCollectorTests extends AbstractCollectorTestCase {

    @Override
    protected int numberOfReplicas() {
        return 0;
    }

    public void testEmptyCluster() throws Exception {
        final String node = internalCluster().startNode();
        waitForNoBlocksOnNode(node);

        try {
            assertThat(newIndexStatsCollector(node).doCollect(), hasSize(0));
        } catch (IndexNotFoundException e) {
            fail("IndexNotFoundException has been thrown but it should have been swallowed by the collector");
        }
    }

    public void testEmptyClusterAllIndices() throws Exception {
        final String node = internalCluster().startNode(settingsBuilder().put(MarvelSettings.INDICES_SETTING.getKey(), MetaData.ALL));
        waitForNoBlocksOnNode(node);

        try {
            assertThat(newIndexStatsCollector(node).doCollect(), hasSize(0));
        } catch (IndexNotFoundException e) {
            fail("IndexNotFoundException has been thrown but it should have been swallowed by the collector");
        }
    }

    public void testEmptyClusterMissingIndex() throws Exception {
        final String node = internalCluster().startNode(settingsBuilder().put(MarvelSettings.INDICES_SETTING.getKey(), "unknown"));
        waitForNoBlocksOnNode(node);

        try {
            assertThat(newIndexStatsCollector(node).doCollect(), hasSize(0));
        } catch (IndexNotFoundException e) {
            fail("IndexNotFoundException has been thrown but it should have been swallowed by the collector");
        }
    }

    public void testIndexStatsCollectorOneIndex() throws Exception {
        final String node = internalCluster().startNode();
        waitForNoBlocksOnNode(node);

        final String indexName = "one-index";
        createIndex(indexName);
        securedEnsureGreen(indexName);

        final int nbDocs = randomIntBetween(1, 20);
        for (int i = 0; i < nbDocs; i++) {
            client().prepareIndex(indexName, "test").setSource("num", i).get();
        }

        securedFlush();
        securedRefresh();

        assertHitCount(client().prepareSearch().setSize(0).get(), nbDocs);

        Collection<MarvelDoc> results = newIndexStatsCollector().doCollect();
        assertThat(results, hasSize(1));

        MarvelDoc marvelDoc = results.iterator().next();
        assertNotNull(marvelDoc);
        assertThat(marvelDoc, instanceOf(IndexStatsMarvelDoc.class));

        IndexStatsMarvelDoc indexStatsMarvelDoc = (IndexStatsMarvelDoc) marvelDoc;
        assertThat(indexStatsMarvelDoc.clusterUUID(), equalTo(client().admin().cluster().prepareState().setMetaData(true).get().getState().metaData().clusterUUID()));
        assertThat(indexStatsMarvelDoc.timestamp(), greaterThan(0L));
        assertThat(indexStatsMarvelDoc.type(), equalTo(IndexStatsCollector.TYPE));

        IndexStats indexStats = indexStatsMarvelDoc.getIndexStats();
        assertNotNull(indexStats);

        assertThat(indexStats.getIndex(), equalTo(indexName));
        assertThat(indexStats.getPrimaries().getDocs().getCount(), equalTo((long) nbDocs));
        assertNotNull(indexStats.getTotal().getStore());
        assertThat(indexStats.getTotal().getStore().getSizeInBytes(), greaterThan(0L));
        assertThat(indexStats.getTotal().getStore().getThrottleTime().millis(), equalTo(0L));
        assertNotNull(indexStats.getTotal().getIndexing());
        assertThat(indexStats.getTotal().getIndexing().getTotal().getThrottleTimeInMillis(), equalTo(0L));
    }

    public void testIndexStatsCollectorMultipleIndices() throws Exception {
        final String node = internalCluster().startNode();
        waitForNoBlocksOnNode(node);

        final String indexPrefix = "multi-indices-";
        final int nbIndices = randomIntBetween(1, 5);
        int[] docsPerIndex = new int[nbIndices];

        for (int i = 0; i < nbIndices; i++) {
            String index = indexPrefix + i;
            createIndex(index);
            securedEnsureGreen(index);

            docsPerIndex[i] = randomIntBetween(1, 20);
            for (int j = 0; j < docsPerIndex[i]; j++) {
                client().prepareIndex(index, "test").setSource("num", i).get();
            }
        }

        securedFlush();
        securedRefresh();

        for (int i = 0; i < nbIndices; i++) {
            assertHitCount(client().prepareSearch(indexPrefix + i).setSize(0).get(), docsPerIndex[i]);
        }

        String clusterUUID = client().admin().cluster().prepareState().setMetaData(true).get().getState().metaData().clusterUUID();

        Collection<MarvelDoc> results = newIndexStatsCollector().doCollect();
        assertThat(results, hasSize(nbIndices));

        for (int i = 0; i < nbIndices; i++) {
            String indexName = indexPrefix + i;
            boolean found = false;

            Iterator<MarvelDoc> it = results.iterator();
            while (!found && it.hasNext()) {
                MarvelDoc marvelDoc = it.next();
                assertThat(marvelDoc, instanceOf(IndexStatsMarvelDoc.class));

                IndexStatsMarvelDoc indexStatsMarvelDoc = (IndexStatsMarvelDoc) marvelDoc;
                IndexStats indexStats = indexStatsMarvelDoc.getIndexStats();
                assertNotNull(indexStats);

                if (indexStats.getIndex().equals(indexPrefix + i)) {
                    assertThat(indexStatsMarvelDoc.clusterUUID(), equalTo(clusterUUID));
                    assertThat(indexStatsMarvelDoc.timestamp(), greaterThan(0L));
                    assertThat(indexStatsMarvelDoc.type(), equalTo(IndexStatsCollector.TYPE));

                    assertThat(indexStats.getIndex(), equalTo(indexName));
                    assertNotNull(indexStats.getTotal().getDocs());
                    assertThat(indexStats.getPrimaries().getDocs().getCount(), equalTo((long) docsPerIndex[i]));
                    assertNotNull(indexStats.getTotal().getStore());
                    assertThat(indexStats.getTotal().getStore().getSizeInBytes(), greaterThanOrEqualTo(0L));
                    assertThat(indexStats.getTotal().getStore().getThrottleTime().millis(), equalTo(0L));
                    assertNotNull(indexStats.getTotal().getIndexing());
                    assertThat(indexStats.getTotal().getIndexing().getTotal().getThrottleTimeInMillis(), equalTo(0L));
                    found = true;
                }
            }
            assertThat("could not find collected stats for index [" + indexPrefix + i + "]", found, is(true));
        }
    }

    public void testIndexStatsCollectorWithLicensing() throws Exception {
        List<String> nodesIds = internalCluster().startNodesAsync(randomIntBetween(2, 5)).get();
        waitForNoBlocksOnNodes();

        try {
            final int nbDocs = randomIntBetween(1, 20);
            for (int i = 0; i < nbDocs; i++) {
                client().prepareIndex("test", "test").setSource("num", i).get();
            }

            securedFlush();
            securedRefresh();
            securedEnsureGreen("test");

            for (String node : nodesIds) {
                logger.debug("--> creating a new instance of the collector");
                IndexStatsCollector collector = newIndexStatsCollector(node);
                assertNotNull(collector);

                logger.debug("--> enabling license and checks that the collector can collect data if node is master");
                enableLicense();
                if (node.equals(internalCluster().getMasterName())) {
                    assertCanCollect(collector);
                } else {
                    assertCannotCollect(collector);
                }

                logger.debug("--> starting graceful period and checks that the collector can still collect data if node is master");
                beginGracefulPeriod();
                if (node.equals(internalCluster().getMasterName())) {
                    assertCanCollect(collector);
                } else {
                    assertCannotCollect(collector);
                }

                logger.debug("--> ending graceful period and checks that the collector cannot collect data");
                endGracefulPeriod();
                assertCannotCollect(collector);

                logger.debug("--> disabling license and checks that the collector cannot collect data");
                disableLicense();
                assertCannotCollect(collector);
            }
        } finally {
            // Ensure license is enabled before finishing the test
            enableLicense();
        }
    }

    private IndexStatsCollector newIndexStatsCollector() {
        // This collector runs on master node only
        return newIndexStatsCollector(internalCluster().getMasterName());
    }

    private IndexStatsCollector newIndexStatsCollector(String nodeId) {
        assertNotNull(nodeId);
        return new IndexStatsCollector(internalCluster().getInstance(Settings.class, nodeId),
                internalCluster().getInstance(ClusterService.class, nodeId),
                internalCluster().getInstance(MarvelSettings.class, nodeId),
                internalCluster().getInstance(MarvelLicensee.class, nodeId),
                securedClient(nodeId));
    }
}
