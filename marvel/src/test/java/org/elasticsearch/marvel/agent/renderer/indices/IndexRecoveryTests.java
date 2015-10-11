/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.renderer.indices;

import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.marvel.agent.collector.indices.IndexRecoveryCollector;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.test.MarvelIntegTestCase;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.junit.After;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.ESIntegTestCase.Scope.TEST;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

@ClusterScope(scope = TEST)
public class IndexRecoveryTests extends MarvelIntegTestCase {

    private static final String INDEX_PREFIX = "test-index-recovery-";

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(MarvelSettings.INTERVAL, "-1")
                .put(MarvelSettings.INDICES, INDEX_PREFIX + "*")
                .put(MarvelSettings.COLLECTORS, IndexRecoveryCollector.NAME)
                .put("marvel.agent.exporters.default_local.type", "local")
                .put("marvel.agent.exporters.default_local.template.settings.index.number_of_replicas", 0)
                .build();
    }

    @After
    public void cleanup() throws Exception {
        updateMarvelInterval(-1, TimeUnit.SECONDS);
        wipeMarvelIndices();
    }

    @Test
    public void testIndexRecovery() throws Exception {
        logger.debug("--> creating some indices so that index recovery collector reports data");
        for (int i = 0; i < randomIntBetween(1, 10); i++) {
            client().prepareIndex(INDEX_PREFIX + i, "foo").setSource("field1", "value1").get();
        }

        logger.debug("--> wait for index recovery collector to collect data");
        assertBusy(new Runnable() {
            @Override
            public void run() {
                securedFlush();
                securedRefresh();

                RecoveryResponse recoveries = client().admin().indices().prepareRecoveries().get();
                assertThat(recoveries.hasRecoveries(), is(true));
            }
        });

        updateMarvelInterval(3L, TimeUnit.SECONDS);
        waitForMarvelIndices();

        awaitMarvelDocsCount(greaterThan(0L), IndexRecoveryCollector.TYPE);

        logger.debug("--> searching for marvel documents of type [{}]", IndexRecoveryCollector.TYPE);
        SearchResponse response = client().prepareSearch(MarvelSettings.MARVEL_INDICES_PREFIX + "*").setTypes(IndexRecoveryCollector.TYPE).get();
        assertThat(response.getHits().getTotalHits(), greaterThan(0L));

        logger.debug("--> checking that every document contains the expected fields");
        String[] filters = {
                IndexRecoveryRenderer.Fields.INDEX_RECOVERY.underscore().toString(),
                IndexRecoveryRenderer.Fields.INDEX_RECOVERY.underscore().toString() + "." + IndexRecoveryRenderer.Fields.SHARDS.underscore().toString(),
        };

        for (SearchHit searchHit : response.getHits().getHits()) {
            Map<String, Object> fields = searchHit.sourceAsMap();

            for (String filter : filters) {
                assertContains(filter, fields);
            }
        }

        logger.debug("--> index recovery successfully collected");
    }
}
