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

package org.elasticsearch.bwcompat;

import com.carrotsearch.randomizedtesting.LifecycleScope;
import com.google.common.util.concurrent.ListenableFuture;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TestUtil;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.indices.get.GetIndexResponse;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.io.FileSystemUtils;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.engine.EngineConfig;
import org.elasticsearch.index.merge.policy.MergePolicyModule;
import org.elasticsearch.index.query.FilterBuilders;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.node.Node;
import org.elasticsearch.rest.action.admin.indices.upgrade.UpgradeTest;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.histogram.Histogram;
import org.elasticsearch.search.aggregations.bucket.terms.Terms;
import org.elasticsearch.search.sort.SortOrder;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.hamcrest.ElasticsearchAssertions;
import org.elasticsearch.test.index.merge.NoMergePolicyProvider;
import org.elasticsearch.test.rest.client.http.HttpRequestBuilder;
import org.hamcrest.Matchers;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@LuceneTestCase.SuppressCodecs({"Lucene3x", "MockFixedIntBlock", "MockVariableIntBlock", "MockSep", "MockRandom", "Lucene40", "Lucene41", "Appending", "Lucene42", "Lucene45", "Lucene46", "Lucene49"})
@ElasticsearchIntegrationTest.ClusterScope(scope = ElasticsearchIntegrationTest.Scope.TEST, numDataNodes = 0)
public class OldIndexBackwardsCompatibilityTests extends ElasticsearchIntegrationTest {
    // TODO: test for proper exception on unsupported indexes (maybe via separate test?)
    // We have a 0.20.6.zip etc for this.

    static List<String> indexes;
    static Path indicesDir;

    @BeforeClass
    public static void initIndexesList() throws Exception {
        indexes = new ArrayList<>();
        URL dirUrl = OldIndexBackwardsCompatibilityTests.class.getResource(".");
        Path dir = Paths.get(dirUrl.toURI());
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "index-*.zip")) {
            for (Path path : stream) {
                indexes.add(path.getFileName().toString());
            }
        }
        Collections.sort(indexes);
    }

    @AfterClass
    public static void tearDownStatics() {
        indexes = null;
        indicesDir = null;
    }

    @Override
    public Settings nodeSettings(int ord) {
        return ImmutableSettings.builder()
            .put(Node.HTTP_ENABLED, true) // for _upgrade
            .put(MergePolicyModule.MERGE_POLICY_TYPE_KEY, NoMergePolicyProvider.class) // disable merging so no segments will be upgraded
            .build();
    }

    void setupCluster() throws Exception {
        ListenableFuture<List<String>> replicas = internalCluster().startNodesAsync(2); // for replicas

        Path dataDir = newTempDirPath(LifecycleScope.SUITE);
        ImmutableSettings.Builder nodeSettings = ImmutableSettings.builder()
            .put("path.data", dataDir.toAbsolutePath())
            .put("node.master", false); // workaround for dangling index loading issue when node is master
        String loadingNode = internalCluster().startNode(nodeSettings.build());

        Path[] nodePaths = internalCluster().getInstance(NodeEnvironment.class, loadingNode).nodeDataPaths();
        assertEquals(1, nodePaths.length);
        indicesDir = nodePaths[0].resolve(NodeEnvironment.INDICES_FOLDER);
        assertFalse(Files.exists(indicesDir));
        Files.createDirectories(indicesDir);

        replicas.get(); // wait for replicas
    }

    String loadIndex(String indexFile) throws Exception {
        Path unzipDir = newTempDirPath();
        Path unzipDataDir = unzipDir.resolve("data");
        String indexName = indexFile.replace(".zip", "").toLowerCase(Locale.ROOT);

        // decompress the index
        Path backwardsIndex = Paths.get(getClass().getResource(indexFile).toURI());
        try (InputStream stream = Files.newInputStream(backwardsIndex)) {
            TestUtil.unzip(stream, unzipDir);
        }

        // check it is unique
        assertTrue(Files.exists(unzipDataDir));
        Path[] list = FileSystemUtils.files(unzipDataDir);
        if (list.length != 1) {
            throw new IllegalStateException("Backwards index must contain exactly one cluster");
        }

        // the bwc scripts packs the indices under this path
        Path src = list[0].resolve("nodes/0/indices/" + indexName);
        Path dest = indicesDir.resolve(indexName);
        assertTrue("[" + indexFile + "] missing index dir: " + src.toString(), Files.exists(src));

        logger.info("--> injecting index [{}] into path [{}]", indexName, dest);
        Files.move(src, dest);
        assertFalse(Files.exists(src));
        assertTrue(Files.exists(dest));

        // force reloading dangling indices with a cluster state republish
        client().admin().cluster().prepareReroute().get();
        ensureGreen(indexName);
        return indexName;
    }

    void unloadIndex(String indexName) throws Exception {
        ElasticsearchAssertions.assertAcked(client().admin().indices().prepareDelete(indexName).get());
        ElasticsearchAssertions.assertAllFilesClosed();
    }

    public void testAllVersionsTested() throws Exception {
        SortedSet<String> expectedVersions = new TreeSet<>();
        for (java.lang.reflect.Field field : Version.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == Version.class) {
                Version v = (Version)field.get(Version.class);
                if (v.snapshot()) continue;  // snapshots are unreleased, so there is no backcompat yet
                if (v.onOrBefore(Version.V_0_20_6)) continue; // we can only test back one major lucene version
                if (v.equals(Version.CURRENT)) continue; // the current version is always compatible with itself

                expectedVersions.add("index-" + v.toString() + ".zip");
            }
        }

        for (String index : indexes) {
            if (expectedVersions.remove(index) == false) {
                logger.warn("Old indexes tests contain extra index: " + index);
            }
        }
        if (expectedVersions.isEmpty() == false) {
            StringBuilder msg = new StringBuilder("Old index tests are missing indexes:");
            for (String expected : expectedVersions) {
                msg.append("\n" + expected);
            }
            fail(msg.toString());
        }
    }

    public void testOldIndexes() throws Exception {
        setupCluster();

        Collections.shuffle(indexes, getRandom());
        for (String index : indexes) {
            long startTime = System.currentTimeMillis();
            logger.info("--> Testing old index " + index);
            assertOldIndexWorks(index);
            logger.info("--> Done testing " + index + ", took " + ((System.currentTimeMillis() - startTime)/1000.0) + " seconds");
        }
    }

    void assertOldIndexWorks(String index) throws Exception {
        Version version = extractVersion(index);
        String indexName = loadIndex(index);
        assertIndexSanity(indexName);
        assertBasicSearchWorks(indexName);
        assertBasicAggregationWorks(indexName);
        assertRealtimeGetWorks(indexName);
        if (version.equals(Version.V_0_90_13) == false) {
            // norelease: 0.90.13 can take too long to create replicas, see https://github.com/elastic/elasticsearch/issues/10434
            assertNewReplicasWork(indexName);
        }
        assertUpgradeWorks(indexName, isLatestLuceneVersion(version));
        assertDeleteByQueryWorked(indexName, version);
        unloadIndex(indexName);
    }

    Version extractVersion(String index) {
        return Version.fromString(index.substring(index.indexOf('-') + 1, index.lastIndexOf('.')));
    }

    boolean isLatestLuceneVersion(Version version) {
        return version.luceneVersion.major == Version.CURRENT.luceneVersion.major &&
               version.luceneVersion.minor == Version.CURRENT.luceneVersion.minor;
    }


    void assertIndexSanity(String indexName) {
        GetIndexResponse getIndexResponse = client().admin().indices().prepareGetIndex().addIndices(indexName).get();
        assertEquals(1, getIndexResponse.indices().length);
        assertEquals(indexName, getIndexResponse.indices()[0]);
        ensureYellow(indexName);
        SearchResponse test = client().prepareSearch(indexName).get();
        assertThat(test.getHits().getTotalHits(), greaterThanOrEqualTo(1l));
    }

    void assertBasicSearchWorks(String indexName) {
        logger.info("--> testing basic search");
        SearchRequestBuilder searchReq = client().prepareSearch(indexName).setQuery(QueryBuilders.matchAllQuery());
        SearchResponse searchRsp = searchReq.get();
        ElasticsearchAssertions.assertNoFailures(searchRsp);
        long numDocs = searchRsp.getHits().getTotalHits();
        logger.info("Found " + numDocs + " in old index");

        logger.info("--> testing basic search with sort");
        searchReq.addSort("long_sort", SortOrder.ASC);
        ElasticsearchAssertions.assertNoFailures(searchReq.get());

        logger.info("--> testing exists filter");
        searchReq = client().prepareSearch(indexName).setQuery(QueryBuilders.filteredQuery(QueryBuilders.matchAllQuery(), FilterBuilders.existsFilter("string")));
        searchRsp = searchReq.get();
        ElasticsearchAssertions.assertNoFailures(searchRsp);
        assertThat(numDocs, equalTo(searchRsp.getHits().getTotalHits()));
    }

    void assertBasicAggregationWorks(String indexName) {
        // histogram on a long
        SearchResponse searchRsp = client().prepareSearch(indexName).addAggregation(AggregationBuilders.histogram("histo").field("long_sort").interval(10)).get();
        ElasticsearchAssertions.assertSearchResponse(searchRsp);
        Histogram histo = searchRsp.getAggregations().get("histo");
        assertNotNull(histo);
        long totalCount = 0;
        for (Histogram.Bucket bucket : histo.getBuckets()) {
            totalCount += bucket.getDocCount();
        }
        assertEquals(totalCount, searchRsp.getHits().getTotalHits());

        // terms on a boolean
        searchRsp = client().prepareSearch(indexName).addAggregation(AggregationBuilders.terms("bool_terms").field("bool")).get();
        Terms terms = searchRsp.getAggregations().get("bool_terms");
        totalCount = 0;
        for (Terms.Bucket bucket : terms.getBuckets()) {
            totalCount += bucket.getDocCount();
        }
        assertEquals(totalCount, searchRsp.getHits().getTotalHits());
    }

    void assertRealtimeGetWorks(String indexName) {
        assertAcked(client().admin().indices().prepareUpdateSettings(indexName).setSettings(ImmutableSettings.builder()
            .put("refresh_interval", -1)
            .build()));
        SearchRequestBuilder searchReq = client().prepareSearch(indexName).setQuery(QueryBuilders.matchAllQuery());
        SearchHit hit = searchReq.get().getHits().getAt(0);
        String docId = hit.getId();
        // foo is new, it is not a field in the generated index
        client().prepareUpdate(indexName, "doc", docId).setDoc("foo", "bar").get();
        GetResponse getRsp = client().prepareGet(indexName, "doc", docId).get();
        Map<String, Object> source = getRsp.getSourceAsMap();
        assertThat(source, Matchers.hasKey("foo"));

        assertAcked(client().admin().indices().prepareUpdateSettings(indexName).setSettings(ImmutableSettings.builder()
            .put("refresh_interval", EngineConfig.DEFAULT_REFRESH_INTERVAL)
            .build()));
    }

    void assertNewReplicasWork(String indexName) throws Exception {
        final int numReplicas = randomIntBetween(1, 2);
        logger.debug("Creating [{}] replicas for index [{}]", numReplicas, indexName);
        assertAcked(client().admin().indices().prepareUpdateSettings(indexName).setSettings(ImmutableSettings.builder()
                .put("number_of_replicas", numReplicas)
        ).execute().actionGet());
        ensureGreen(indexName);

        // TODO: do something with the replicas! query? index?
    }

    // #10067: create-bwc-index.py deleted any doc with long_sort:[10-20]
    void assertDeleteByQueryWorked(String indexName, Version version) throws Exception {
        if (version.onOrBefore(Version.V_1_0_0_Beta2)) {
            // TODO: remove this once #10262 is fixed
            return;
        }
        SearchRequestBuilder searchReq = client().prepareSearch(indexName).setQuery(QueryBuilders.queryStringQuery("long_sort:[10 TO 20]"));
        assertEquals(0, searchReq.get().getHits().getTotalHits());
    }

    void assertUpgradeWorks(String indexName, boolean alreadyLatest) throws Exception {
        HttpRequestBuilder httpClient = httpClient();

        if (alreadyLatest == false) {
            UpgradeTest.assertNotUpgraded(httpClient, indexName);
        }
        UpgradeTest.runUpgrade(httpClient, indexName);
        UpgradeTest.assertUpgraded(httpClient, indexName);
    }
}
