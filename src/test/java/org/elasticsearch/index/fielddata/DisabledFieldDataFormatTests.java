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

package org.elasticsearch.index.fielddata;

import com.google.common.base.Predicate;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.mapper.MapperService.SmartNameFieldMappers;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.Aggregator.SubAggCollectionMode;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.hamcrest.Matchers;

import java.util.Set;
import java.util.concurrent.Callable;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertFailures;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@ClusterScope(randomDynamicTemplates = false)
public class DisabledFieldDataFormatTests extends ElasticsearchIntegrationTest {

    public void test() throws Exception {
        prepareCreate("test").addMapping("type", "s", "type=string").execute().actionGet();
        ensureGreen();
        logger.info("indexing data start");
        for (int i = 0; i < 10; ++i) {
            client().prepareIndex("test", "type", Integer.toString(i)).setSource("s", "value" + i).execute().actionGet();
        }
        logger.info("indexing data end");

        final int searchCycles = 20;

        refresh();

        // disable field data
        updateFormat("disabled");

        SubAggCollectionMode aggCollectionMode = randomFrom(SubAggCollectionMode.values());
        SearchResponse resp = null;
        // try to run something that relies on field data and make sure that it fails
        for (int i = 0; i < searchCycles; i++) {
            try {
                resp = client().prepareSearch("test").setPreference(Integer.toString(i)).addAggregation(AggregationBuilders.terms("t").field("s")
                        .collectMode(aggCollectionMode)).execute().actionGet();
                assertFailures(resp);
            } catch (SearchPhaseExecutionException e) {
                // expected
            }
        }

        // enable it again
        updateFormat("paged_bytes");

        // try to run something that relies on field data and make sure that it works
        for (int i = 0; i < searchCycles; i++) {
            resp = client().prepareSearch("test").setPreference(Integer.toString(i)).addAggregation(AggregationBuilders.terms("t").field("s")
                    .collectMode(aggCollectionMode)).execute().actionGet();
            assertNoFailures(resp);
        }

        // disable it again
        updateFormat("disabled");

        // this time, it should work because segments are already loaded
        for (int i = 0; i < searchCycles; i++) {
            resp = client().prepareSearch("test").setPreference(Integer.toString(i)).addAggregation(AggregationBuilders.terms("t").field("s")
                    .collectMode(aggCollectionMode)).execute().actionGet();
            assertNoFailures(resp);
        }

        // but add more docs and the new segment won't be loaded
        client().prepareIndex("test", "type", "-1").setSource("s", "value").execute().actionGet();
        refresh();
        for (int i = 0; i < searchCycles; i++) {
            try {
                resp = client().prepareSearch("test").setPreference(Integer.toString(i)).addAggregation(AggregationBuilders.terms("t").field("s")
                        .collectMode(aggCollectionMode)).execute().actionGet();
                assertFailures(resp);
            } catch (SearchPhaseExecutionException e) {
                // expected
            }
        }
    }

    private void updateFormat(final String format) throws Exception {
        logger.info(">> put mapping start {}", format);
        assertAcked(client().admin().indices().preparePutMapping("test").setType("type").setSource(
                XContentFactory.jsonBuilder().startObject().startObject("type")
                        .startObject("properties")
                            .startObject("s")
                                .field("type", "string")
                                .startObject("fielddata")
                                    .field("format", format)
                                .endObject()
                            .endObject()
                        .endObject()
                        .endObject()
                        .endObject()).get());
        logger.info(">> put mapping end {}", format);
        assertBusy(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                Set<String> nodes = internalCluster().nodesInclude("test");
                assertFalse(nodes.isEmpty());
                for (String node : nodes) {
                    IndicesService indicesService = internalCluster().getInstance(IndicesService.class, node);
                    IndexService indexService = indicesService.indexService("test");
                    assertThat(indexService, notNullValue());
                    final SmartNameFieldMappers mappers = indexService.mapperService().smartName("s");
                    assertThat(mappers, notNullValue());
                    assertTrue(mappers.hasMapper());
                    final String currentFormat = mappers.mapper().fieldDataType().getFormat(ImmutableSettings.EMPTY);
                    assertThat(currentFormat, equalTo(format));
                }
                return null;
            }
        });
    }

}
