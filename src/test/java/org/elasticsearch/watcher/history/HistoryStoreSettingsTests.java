/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.history;

import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.watcher.test.AbstractWatcherIntegrationTests;
import org.junit.Test;

import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.TEST;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;

/**
 */
@TestLogging("cluster:DEBUG,action.admin.cluster.settings:DEBUG,watcher:DEBUG")
@ElasticsearchIntegrationTest.ClusterScope(scope = TEST, numClientNodes = 0, transportClientRatio = 0, randomDynamicTemplates = false, numDataNodes = 1)
public class HistoryStoreSettingsTests extends AbstractWatcherIntegrationTests {

    @Test
    public void testChangeSettings() throws Exception {
        GetIndexTemplatesResponse response = client().admin().indices().prepareGetTemplates(HistoryStore.INDEX_TEMPLATE_NAME).get();
        assertThat(response.getIndexTemplates().get(0).getSettings().get("index.number_of_shards"), equalTo("1"));
        assertThat(response.getIndexTemplates().get(0).getSettings().get("index.number_of_replicas"), nullValue()); // this isn't defined in the template, so we rely on ES's default, which is zero
        assertThat(response.getIndexTemplates().get(0).getSettings().get("index.refresh_interval"), nullValue()); // this isn't defined in the template, so we rely on ES's default, which is 1s
        assertAcked(
                client().admin().cluster().prepareUpdateSettings()
                        .setTransientSettings(Settings.builder()
                                .put("watcher.history.index.number_of_shards", "2")
                                .put("watcher.history.index.number_of_replicas", "2")
                                .put("watcher.history.index.refresh_interval", "5m"))
                        .get()
        );

        // use assertBusy(...) because we update the index template in an async manner
        assertBusy(new Runnable() {
            @Override
            public void run() {
                GetIndexTemplatesResponse response = client().admin().indices().prepareGetTemplates(HistoryStore.INDEX_TEMPLATE_NAME).get();
                assertThat(response.getIndexTemplates().get(0).getSettings().get("index.number_of_shards"), equalTo("2"));
                assertThat(response.getIndexTemplates().get(0).getSettings().get("index.number_of_replicas"), equalTo("2"));
                assertThat(response.getIndexTemplates().get(0).getSettings().get("index.refresh_interval"), equalTo("5m"));
            }
        });
    }

    @Test
    public void testChangeSettings_ignoringForbiddenSetting() throws Exception {
        GetIndexTemplatesResponse response = client().admin().indices().prepareGetTemplates(HistoryStore.INDEX_TEMPLATE_NAME).get();
        assertThat(response.getIndexTemplates().get(0).getSettings().get("index.number_of_shards"), equalTo("1"));
        assertThat(response.getIndexTemplates().get(0).getSettings().getAsBoolean("index.mapper.dynamic", null), is(false));

        assertAcked(
                client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(Settings.builder()
                        .put("watcher.history.index.number_of_shards", "2")
                        .put("watcher.history.index.mapper.dynamic", true)) // forbidden setting, should not get updated
                .get()
        );

        // use assertBusy(...) because we update the index template in an async manner
        assertBusy(new Runnable() {
            @Override
            public void run() {
                GetIndexTemplatesResponse response = client().admin().indices().prepareGetTemplates(HistoryStore.INDEX_TEMPLATE_NAME).get();
                assertThat(response.getIndexTemplates().get(0).getSettings().get("index.number_of_shards"), equalTo("2"));
                assertThat(response.getIndexTemplates().get(0).getSettings().getAsBoolean("index.mapper.dynamic", null), is(false));
            }
        });
    }

}
