/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.exporter.local;

import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.action.admin.indices.template.get.GetIndexTemplatesResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.marvel.agent.collector.cluster.ClusterStateCollector;
import org.elasticsearch.marvel.agent.collector.cluster.ClusterStateMarvelDoc;
import org.elasticsearch.marvel.agent.collector.indices.IndexRecoveryCollector;
import org.elasticsearch.marvel.agent.collector.indices.IndexRecoveryMarvelDoc;
import org.elasticsearch.marvel.agent.exporter.Exporter;
import org.elasticsearch.marvel.agent.exporter.Exporters;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.test.MarvelIntegTestCase;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.test.InternalTestCluster;
import org.elasticsearch.test.junit.annotations.TestLogging;
import org.joda.time.format.DateTimeFormat;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.elasticsearch.marvel.agent.exporter.http.HttpExporter.MIN_SUPPORTED_TEMPLATE_VERSION;
import static org.elasticsearch.marvel.agent.exporter.http.HttpExporterUtils.MARVEL_VERSION_FIELD;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.*;

@ClusterScope(scope = Scope.SUITE, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public class LocalExporterTests extends MarvelIntegTestCase {

    private final static AtomicLong timeStampGenerator = new AtomicLong();

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
//                .put(MarvelSettings.STARTUP_DELAY, "1h")
                .build();
    }

    @Test
    public void testSimpleExport() throws Exception {
        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .put("marvel.agent.exporters._local.enabled", true)
                .build());
        ensureGreen();

        Exporter exporter = getLocalExporter("_local");

        logger.debug("--> exporting a single marvel doc");
        exporter.export(Collections.singletonList(newRandomMarvelDoc()));
        awaitMarvelDocsCount(is(1L));

        deleteMarvelIndices();

        final List<MarvelDoc> marvelDocs = new ArrayList<>();
        for (int i=0; i < randomIntBetween(2, 50); i++) {
            marvelDocs.add(newRandomMarvelDoc());
        }

        logger.debug("--> exporting {} marvel docs", marvelDocs.size());
        exporter.export(marvelDocs);
        awaitMarvelDocsCount(is((long) marvelDocs.size()));

        SearchResponse response = client().prepareSearch(MarvelSettings.MARVEL_INDICES_PREFIX + "*").get();
        for (SearchHit hit : response.getHits().hits()) {
            Map<String, Object> source = hit.sourceAsMap();
            assertNotNull(source.get("cluster_uuid"));
            assertNotNull(source.get("timestamp"));
        }
    }

    @Test
    public void testTemplateCreation() throws Exception {
        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .build());
        ensureGreen();

        LocalExporter exporter = getLocalExporter("_local");
        assertTrue(exporter.installedTemplateVersionMandatesAnUpdate(Version.CURRENT, null));

        // lets wait until the marvel template will be installed
        awaitMarvelTemplateInstalled();

        awaitMarvelDocsCount(greaterThan(0L));

        assertThat(getCurrentlyInstalledTemplateVersion(), is(Version.CURRENT));
    }

    @Test
    public void testTemplateUpdate() throws Exception {
        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .build());
        ensureGreen();

        LocalExporter exporter = getLocalExporter("_local");
        Version fakeVersion = MIN_SUPPORTED_TEMPLATE_VERSION;
        assertThat(exporter.installedTemplateVersionMandatesAnUpdate(Version.CURRENT, fakeVersion), is(true));

        // first, lets wait for the marvel template to be installed
        awaitMarvelTemplateInstalled();

        // now lets update the template with an old one and then restart the cluster
        exporter.putTemplate(Settings.builder().put(MARVEL_VERSION_FIELD, fakeVersion.toString()).build());
        logger.debug("full cluster restart");
        final CountDownLatch latch = new CountDownLatch(1);
        internalCluster().fullRestart(new InternalTestCluster.RestartCallback() {
            @Override
            public void doAfterNodes(int n, Client client) throws Exception {
                latch.countDown();
            }
        });
        if (!latch.await(30, TimeUnit.SECONDS)) {
            fail("waited too long (at least 30 seconds) for the cluster to restart");
        }

        // now that the cluster is restarting, lets wait for the new template version to be installed
        awaitMarvelTemplateInstalled(Version.CURRENT);
    }

    //TODO needs a rewrite, the `start(ClusterState)` should be unit tested
//    @Test @AwaitsFix(bugUrl = "LocalExporter#210")
//    public void testUnsupportedTemplateVersion() throws Exception {
//        internalCluster().startNode(Settings.builder()
//                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
//                .build());
//        ensureGreen();
//
//        LocalExporter exporter = getLocalExporter("_local");
//
//        Version fakeVersion = randomFrom(Version.V_0_18_0, Version.V_1_0_0, Version.V_1_4_0);
//        assertFalse(exporter.shouldUpdateTemplate(fakeVersion, Version.CURRENT));
//
//        logger.debug("--> creating the marvel template with a fake version [{}]", fakeVersion);
//        exporter.putTemplate(Settings.builder().put(MARVEL_VERSION_FIELD, fakeVersion.toString()).build());
//        assertMarvelTemplateInstalled();
//
//        assertThat(exporter.templateVersion(), equalTo(fakeVersion));
//
//        logger.debug("--> exporting when the marvel template is tool old: no document is exported and the template is not updated");
//        awaitMarvelDocsCount(is(0L));
//        exporter.export(Collections.singletonList(newRandomMarvelDoc()));
//        awaitMarvelDocsCount(is(0L));
//        assertMarvelTemplateInstalled();
//
//        assertThat(exporter.templateVersion(), equalTo(fakeVersion));
//    }

    @Test @TestLogging("marvel.agent:debug")
    public void testIndexTimestampFormat() throws Exception {
        long time = System.currentTimeMillis();
        final String timeFormat = randomFrom("YY", "YYYY", "YYYY.MM", "YYYY-MM", "MM.YYYY", "MM");
        String expectedIndexName = MarvelSettings.MARVEL_INDICES_PREFIX + DateTimeFormat.forPattern(timeFormat).withZoneUTC().print(time);

        internalCluster().startNode(Settings.builder()
                .put("marvel.agent.exporters._local.type", LocalExporter.TYPE)
                .put("marvel.agent.exporters._local." + LocalExporter.INDEX_NAME_TIME_FORMAT_SETTING, timeFormat)
                .build());
        ensureGreen();

        LocalExporter exporter = getLocalExporter("_local");

        assertThat(exporter.indexNameResolver().resolve(time), equalTo(expectedIndexName));

        logger.debug("--> exporting a random marvel document");
        MarvelDoc doc = newRandomMarvelDoc();
        exporter.export(Collections.singletonList(doc));
        awaitMarvelDocsCount(is(1L));
        expectedIndexName = MarvelSettings.MARVEL_INDICES_PREFIX + DateTimeFormat.forPattern(timeFormat).withZoneUTC().print(doc.timestamp());

        logger.debug("--> check that the index [{}] has the correct timestamp [{}]", timeFormat, expectedIndexName);
        assertThat(client().admin().indices().prepareExists(expectedIndexName).get().isExists(), is(true));

        logger.debug("--> updates the timestamp");
        final String newTimeFormat = randomFrom("dd", "dd.MM.YYYY", "dd.MM");

        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder()
                .put("marvel.agent.exporters._local.index.name.time_format", newTimeFormat)));

        logger.debug("--> exporting a random marvel document");
        doc = newRandomMarvelDoc();
        exporter.export(Collections.singletonList(doc));
        awaitMarvelDocsCount(is(1L));
        String newExpectedIndexName = MarvelSettings.MARVEL_INDICES_PREFIX + DateTimeFormat.forPattern(timeFormat).withZoneUTC().print(doc.timestamp());

        logger.debug("--> check that the index [{}] has the correct timestamp [{}]", newTimeFormat, newExpectedIndexName);
        assertThat(exporter.indexNameResolver().resolve(doc.timestamp()), equalTo(newExpectedIndexName));
        assertTrue(client().admin().indices().prepareExists(newExpectedIndexName).get().isExists());
    }

    private LocalExporter getLocalExporter(String name) throws Exception {
        final Exporter exporter = internalCluster().getInstance(Exporters.class).getExporter(name);
        assertThat(exporter, notNullValue());
        assertThat(exporter, instanceOf(LocalExporter.class));
        return (LocalExporter) exporter;
    }

    private MarvelDoc newRandomMarvelDoc() {
        if (randomBoolean()) {
            return new IndexRecoveryMarvelDoc(internalCluster().getClusterName(),
                    IndexRecoveryCollector.TYPE, timeStampGenerator.incrementAndGet(), new RecoveryResponse());
        } else {
            return new ClusterStateMarvelDoc(internalCluster().getClusterName(),
                    ClusterStateCollector.TYPE, timeStampGenerator.incrementAndGet(), ClusterState.PROTO, ClusterHealthStatus.GREEN);
        }
    }

    private void awaitMarvelTemplateInstalled() throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertMarvelTemplateInstalled();
            }
        }, 30, TimeUnit.SECONDS);
    }

    private void awaitMarvelTemplateInstalled(Version version) throws Exception {
        assertBusy(new Runnable() {
            @Override
            public void run() {
                assertMarvelTemplateInstalled(version);
            }
        }, 30, TimeUnit.SECONDS);
    }

    protected void assertMarvelTemplateInstalled(Version version) {
        for (IndexTemplateMetaData template : client().admin().indices().prepareGetTemplates(Exporter.INDEX_TEMPLATE_NAME).get().getIndexTemplates()) {
            if (template.getName().equals(Exporter.INDEX_TEMPLATE_NAME)) {
                Version templateVersion = LocalExporter.templateVersion(template);
                if (templateVersion != null && templateVersion.id == version.id) {
                    return;
                }
                fail("did not find marvel template with expected version [" + version + "]. found version [" + templateVersion + "]");
            }
        }
        fail("marvel template could not be found");
    }

    private Version getCurrentlyInstalledTemplateVersion() {
        GetIndexTemplatesResponse response = client().admin().indices().prepareGetTemplates(Exporter.INDEX_TEMPLATE_NAME).get();
        assertThat(response, notNullValue());
        assertThat(response.getIndexTemplates(), notNullValue());
        assertThat(response.getIndexTemplates(), hasSize(1));
        assertThat(response.getIndexTemplates().get(0), notNullValue());
        return LocalExporter.templateVersion(response.getIndexTemplates().get(0));
    }
}
