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
package org.elasticsearch.index.engine.internal;

import com.carrotsearch.randomizedtesting.annotations.Nightly;
import com.carrotsearch.randomizedtesting.annotations.Seed;
import com.google.common.base.Predicate;
import org.apache.lucene.index.LogByteSizeMergePolicy;
import org.apache.lucene.util.LuceneTestCase;
import org.elasticsearch.action.admin.indices.stats.IndicesStatsResponse;
import org.elasticsearch.action.bulk.BulkRequestBuilder;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.index.merge.policy.LogDocMergePolicyProvider;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;

/**
 */
@ElasticsearchIntegrationTest.ClusterScope(numNodes = 1, scope = ElasticsearchIntegrationTest.Scope.SUITE)
public class InternalEngineMergeTests extends ElasticsearchIntegrationTest {

    @Test
    @LuceneTestCase.Slow
    public void testMergesHappening() throws InterruptedException, IOException, ExecutionException {
        final int numOfShards = 5;
        // some settings to keep num segments low
        assertAcked(prepareCreate("test").setSettings(ImmutableSettings.builder()
                .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, numOfShards)
                .put(LogDocMergePolicyProvider.MIN_MERGE_DOCS_KEY, 10)
                .put(LogDocMergePolicyProvider.MERGE_FACTORY_KEY, 5)
                .put(LogByteSizeMergePolicy.DEFAULT_MIN_MERGE_MB, 0.5)
                .build()));
        long id = 0;
        final int rounds = scaledRandomIntBetween(50, 300);
        logger.info("Starting rounds [{}] ", rounds);
        for (int i = 0; i < rounds; ++i) {
            final int numDocs = scaledRandomIntBetween(100, 1000);
            BulkRequestBuilder request = client().prepareBulk();
            for (int j = 0; j < numDocs; ++j) {
                request.add(Requests.indexRequest("test").type("type1").id(Long.toString(id++)).source(jsonBuilder().startObject().field("l", randomLong()).endObject()));
            }
            BulkResponse response = request.execute().actionGet();
            refresh();
            assertNoFailures(response);
            IndicesStatsResponse stats = client().admin().indices().prepareStats("test").setSegments(true).setMerge(true).get();
            logger.info("index round [{}] - segments {}, total merges {}, current merge {}", i, stats.getPrimaries().getSegments().getCount(), stats.getPrimaries().getMerge().getTotal(), stats.getPrimaries().getMerge().getCurrent());
        }
        awaitBusy(new Predicate<Object>() {
            @Override
            public boolean apply(Object input) {
                IndicesStatsResponse stats = client().admin().indices().prepareStats().setSegments(true).setMerge(true).get();
                logger.info("numshards {}, segments {}, total merges {}, current merge {}", numOfShards, stats.getPrimaries().getSegments().getCount(), stats.getPrimaries().getMerge().getTotal(), stats.getPrimaries().getMerge().getCurrent());
                long current = stats.getPrimaries().getMerge().getCurrent();
                long count = stats.getPrimaries().getSegments().getCount();
                return count < 50 && current == 0;
            }
        });
        IndicesStatsResponse stats = client().admin().indices().prepareStats().setSegments(true).setMerge(true).get();
        logger.info("numshards {}, segments {}, total merges {}, current merge {}", numOfShards, stats.getPrimaries().getSegments().getCount(), stats.getPrimaries().getMerge().getTotal(), stats.getPrimaries().getMerge().getCurrent());
        long count = stats.getPrimaries().getSegments().getCount();
        assertThat(count, Matchers.lessThanOrEqualTo(50l));
    }

}
