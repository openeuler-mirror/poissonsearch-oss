/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.exporter.http;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.QueueDispatcher;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthStatus;
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.marvel.agent.collector.cluster.ClusterStateCollector;
import org.elasticsearch.marvel.agent.collector.cluster.ClusterStateMarvelDoc;
import org.elasticsearch.marvel.agent.collector.indices.IndexRecoveryCollector;
import org.elasticsearch.marvel.agent.collector.indices.IndexRecoveryMarvelDoc;
import org.elasticsearch.marvel.agent.exporter.Exporters;
import org.elasticsearch.marvel.agent.exporter.MarvelDoc;
import org.elasticsearch.marvel.agent.exporter.MarvelTemplateUtils;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.test.MarvelIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.hamcrest.Matchers;
import org.joda.time.format.DateTimeFormat;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.net.BindException;
import java.util.Collections;
import java.util.Map;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

@ESIntegTestCase.ClusterScope(scope = Scope.TEST, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public class HttpExporterTests extends MarvelIntegTestCase {
    private int webPort;
    private MockWebServer webServer;

    @Before
    public void startWebservice() throws Exception {
        for (webPort = 9250; webPort < 9300; webPort++) {
            try {
                webServer = new MockWebServer();
                QueueDispatcher dispatcher = new QueueDispatcher();
                dispatcher.setFailFast(true);
                webServer.setDispatcher(dispatcher);
                webServer.start(webPort);
                return;
            } catch (BindException be) {
                logger.warn("port [{}] was already in use trying next port", webPort);
            }
        }
        throw new ElasticsearchException("unable to find open port between 9200 and 9300");
    }

    @After
    public void cleanup() throws Exception {
        stopCollection();
        webServer.shutdown();
    }

    public void testExport() throws Exception {
        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueResponse(404, "marvel template does not exist");
        enqueueResponse(201, "marvel template created");
        enqueueResponse(200, "successful bulk request ");

        Settings.Builder builder = Settings.builder()
                .put(MarvelSettings.INTERVAL, "-1")
                .put("marvel.agent.exporters._http.type", "http")
                .put("marvel.agent.exporters._http.host", webServer.getHostName() + ":" + webServer.getPort())
                .put("marvel.agent.exporters._http.connection.keep_alive", false);

        String agentNode = internalCluster().startNode(builder);
        HttpExporter exporter = getExporter(agentNode);
        MarvelDoc doc = newRandomMarvelDoc();
        exporter.export(Collections.singletonList(doc));

        assertThat(webServer.getRequestCount(), greaterThanOrEqualTo(4));

        RecordedRequest recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("PUT"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));
        assertThat(recordedRequest.getBody().readByteArray(), equalTo(MarvelTemplateUtils.loadDefaultTemplate()));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("POST"));
        assertThat(recordedRequest.getPath(), equalTo("/_bulk"));
    }

    public void testDynamicHostChange() {
        // disable exporting to be able to use non valid hosts
        Settings.Builder builder = Settings.builder()
                .put(MarvelSettings.INTERVAL, "-1")
                .put("marvel.agent.exporters._http.type", "http")
                .put("marvel.agent.exporters._http.host", "test0");

        String nodeName = internalCluster().startNode(builder);

        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder()
                .putArray("marvel.agent.exporters._http.host", "test1")));
        assertThat(getExporter(nodeName).hosts, Matchers.arrayContaining("test1"));

        // wipes the non array settings
        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder()
                .putArray("marvel.agent.exporters._http.host", "test2")
                .put("marvel.agent.exporters._http.host", "")));
        assertThat(getExporter(nodeName).hosts, Matchers.arrayContaining("test2"));

        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder()
                .putArray("marvel.agent.exporters._http.host", "test3")));
        assertThat(getExporter(nodeName).hosts, Matchers.arrayContaining("test3"));
    }

    public void testTemplateUpdate() throws Exception {
        Settings.Builder builder = Settings.builder()
                .put(MarvelSettings.INTERVAL, "-1")
                .put("marvel.agent.exporters._http.type", "http")
                .put("marvel.agent.exporters._http.host", webServer.getHostName() + ":" + webServer.getPort())
                .put("marvel.agent.exporters._http.connection.keep_alive", false);

        logger.info("--> starting node");

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueResponse(404, "marvel template does not exist");
        enqueueResponse(201, "marvel template created");
        enqueueResponse(200, "successful bulk request ");

        String agentNode = internalCluster().startNode(builder);

        logger.info("--> exporting data");
        HttpExporter exporter = getExporter(agentNode);
        exporter.export(Collections.singletonList(newRandomMarvelDoc()));

        assertThat(webServer.getRequestCount(), greaterThanOrEqualTo(4));

        RecordedRequest recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("PUT"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));
        assertThat(recordedRequest.getBody().readByteArray(), equalTo(MarvelTemplateUtils.loadDefaultTemplate()));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("POST"));
        assertThat(recordedRequest.getPath(), equalTo("/_bulk"));
    }

    public void testHostChangeReChecksTemplate() throws Exception {

        Settings.Builder builder = Settings.builder()
                .put(MarvelSettings.INTERVAL, "-1")
                .put("marvel.agent.exporters._http.type", "http")
                .put("marvel.agent.exporters._http.host", webServer.getHostName() + ":" + webServer.getPort())
                .put("marvel.agent.exporters._http.connection.keep_alive", false);

        logger.info("--> starting node");

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueResponse(404, "marvel template does not exist");
        enqueueResponse(201, "marvel template created");
        enqueueResponse(200, "successful bulk request ");

        String agentNode = internalCluster().startNode(builder);

        logger.info("--> exporting data");
        HttpExporter exporter = getExporter(agentNode);
        exporter.export(Collections.singletonList(newRandomMarvelDoc()));

        assertThat(webServer.getRequestCount(), greaterThanOrEqualTo(4));

        RecordedRequest recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("PUT"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));
        assertThat(recordedRequest.getBody().readByteArray(), equalTo(MarvelTemplateUtils.loadDefaultTemplate()));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("POST"));
        assertThat(recordedRequest.getPath(), equalTo("/_bulk"));

        logger.info("--> setting up another web server");
        MockWebServer secondWebServer = null;
        int secondWebPort;

        try {
            for (secondWebPort = 9250; secondWebPort < 9300; secondWebPort++) {
                try {
                    secondWebServer = new MockWebServer();
                    QueueDispatcher dispatcher = new QueueDispatcher();
                    dispatcher.setFailFast(true);
                    secondWebServer.setDispatcher(dispatcher);
                    secondWebServer.start(secondWebPort);
                    break;
                } catch (BindException be) {
                    logger.warn("port [{}] was already in use trying next port", secondWebPort);
                }
            }

            assertNotNull("Unable to start the second mock web server", secondWebServer);

            assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(
                    Settings.builder().putArray("marvel.agent.exporters._http.host", secondWebServer.getHostName() + ":" + secondWebServer.getPort())).get());

            // a new exporter is created on update, so we need to re-fetch it
            exporter = getExporter(agentNode);

            enqueueGetClusterVersionResponse(secondWebServer, Version.CURRENT);
            enqueueResponse(secondWebServer, 404, "marvel template does not exist");
            enqueueResponse(secondWebServer, 201, "marvel template created");
            enqueueResponse(secondWebServer, 200, "successful bulk request ");

            logger.info("--> exporting a second event");
            exporter.export(Collections.singletonList(newRandomMarvelDoc()));

            assertThat(secondWebServer.getRequestCount(), greaterThanOrEqualTo(4));

            recordedRequest = secondWebServer.takeRequest();
            assertThat(recordedRequest.getMethod(), equalTo("GET"));
            assertThat(recordedRequest.getPath(), equalTo("/"));

            recordedRequest = secondWebServer.takeRequest();
            assertThat(recordedRequest.getMethod(), equalTo("GET"));
            assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));

            recordedRequest = secondWebServer.takeRequest();
            assertThat(recordedRequest.getMethod(), equalTo("PUT"));
            assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));
            assertThat(recordedRequest.getBody().readByteArray(), equalTo(MarvelTemplateUtils.loadDefaultTemplate()));

            recordedRequest = secondWebServer.takeRequest();
            assertThat(recordedRequest.getMethod(), equalTo("POST"));
            assertThat(recordedRequest.getPath(), equalTo("/_bulk"));

        } finally {
            if (secondWebServer != null) {
                secondWebServer.shutdown();
            }
        }
    }

    public void testUnsupportedTemplateVersion() throws Exception {
        Settings.Builder builder = Settings.builder()
                .put(MarvelSettings.INTERVAL, "-1")
                .put("marvel.agent.exporters._http.type", "http")
                .put("marvel.agent.exporters._http.host", webServer.getHostName() + ":" + webServer.getPort())
                .put("marvel.agent.exporters._http.connection.keep_alive", false);

        logger.info("--> starting node");

        enqueueGetClusterVersionResponse(Version.CURRENT);
        // returning a fake template with an unsupported version
        Version unsupportedVersion = randomFrom(Version.V_0_18_0, Version.V_1_0_0, Version.V_1_4_0);
        enqueueResponse(200, XContentHelper.toString(Settings.builder().put("index.marvel_version", unsupportedVersion.toString()).build()));

        String agentNode = internalCluster().startNode(builder);

        logger.info("--> exporting data");
        HttpExporter exporter = getExporter(agentNode);
        exporter.export(Collections.singletonList(newRandomMarvelDoc()));

        assertThat(webServer.getRequestCount(), greaterThanOrEqualTo(3));

        RecordedRequest recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));
    }

    public void testDynamicIndexFormatChange() throws Exception {
        Settings.Builder builder = Settings.builder()
                .put(MarvelSettings.INTERVAL, "-1")
                .put("marvel.agent.exporters._http.type", "http")
                .put("marvel.agent.exporters._http.host", webServer.getHostName() + ":" + webServer.getPort())
                .put("marvel.agent.exporters._http.connection.keep_alive", false);

        String agentNode = internalCluster().startNode(builder);

        logger.info("--> exporting a first event");

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueResponse(404, "marvel template does not exist");
        enqueueResponse(201, "marvel template created");
        enqueueResponse(200, "successful bulk request ");

        HttpExporter exporter = getExporter(agentNode);

        MarvelDoc doc = newRandomMarvelDoc();
        exporter.export(Collections.singletonList(doc));

        assertThat(webServer.getRequestCount(), greaterThanOrEqualTo(4));

        RecordedRequest recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("PUT"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));
        assertThat(recordedRequest.getBody().readByteArray(), equalTo(MarvelTemplateUtils.loadDefaultTemplate()));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("POST"));
        assertThat(recordedRequest.getPath(), equalTo("/_bulk"));

        String indexName = exporter.indexNameResolver().resolve(doc);
        logger.info("--> checks that the document in the bulk request is indexed in [{}]", indexName);

        byte[] bytes = recordedRequest.getBody().readByteArray();
        Map<String, Object> data = XContentHelper.convertToMap(new BytesArray(bytes), false).v2();
        Map<String, Object> index = (Map<String, Object>) data.get("index");
        assertThat(index.get("_index"), equalTo(indexName));

        String newTimeFormat = randomFrom("YY", "YYYY", "YYYY.MM", "YYYY-MM", "MM.YYYY", "MM");
        logger.info("--> updating index time format setting to {}", newTimeFormat);
        assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(Settings.builder()
                .put("marvel.agent.exporters._http.index.name.time_format", newTimeFormat)));


        logger.info("--> exporting a second event");

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueResponse(404, "marvel template does not exist");
        enqueueResponse(201, "marvel template created");
        enqueueResponse(200, "successful bulk request ");

        doc = newRandomMarvelDoc();
        exporter = getExporter(agentNode);
        exporter.export(Collections.singletonList(doc));

        String expectedMarvelIndex = MarvelSettings.MARVEL_INDICES_PREFIX
                + DateTimeFormat.forPattern(newTimeFormat).withZoneUTC().print(doc.timestamp());

        assertThat(webServer.getRequestCount(), greaterThanOrEqualTo(4));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("GET"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("PUT"));
        assertThat(recordedRequest.getPath(), equalTo("/_template/.marvel-es"));
        assertThat(recordedRequest.getBody().readByteArray(), equalTo(MarvelTemplateUtils.loadDefaultTemplate()));

        recordedRequest = webServer.takeRequest();
        assertThat(recordedRequest.getMethod(), equalTo("POST"));
        assertThat(recordedRequest.getPath(), equalTo("/_bulk"));

        logger.info("--> checks that the document in the bulk request is indexed in [{}]", expectedMarvelIndex);

        bytes = recordedRequest.getBody().readByteArray();
        data = XContentHelper.convertToMap(new BytesArray(bytes), false).v2();
        index = (Map<String, Object>) data.get("index");
        assertThat(index.get("_index"), equalTo(expectedMarvelIndex));
    }

    public void testLoadRemoteClusterVersion() throws IOException {
        final String host = webServer.getHostName() + ":" + webServer.getPort();

        Settings.Builder builder = Settings.builder()
                .put(MarvelSettings.INTERVAL, "-1")
                .put("marvel.agent.exporters._http.type", "http")
                .put("marvel.agent.exporters._http.host", host)
                .put("marvel.agent.exporters._http.connection.keep_alive", false);

        String agentNode = internalCluster().startNode(builder);
        HttpExporter exporter = getExporter(agentNode);

        enqueueGetClusterVersionResponse(Version.CURRENT);
        Version resolved = exporter.loadRemoteClusterVersion(host);
        assertTrue(resolved.equals(Version.CURRENT));

        final Version expected = randomFrom(Version.CURRENT, Version.V_0_18_0, Version.V_1_1_0, Version.V_1_2_5, Version.V_1_4_5, Version.V_1_6_0);
        enqueueGetClusterVersionResponse(expected);
        resolved = exporter.loadRemoteClusterVersion(host);
        assertTrue(resolved.equals(expected));
    }

    private HttpExporter getExporter(String nodeName) {
        Exporters exporters = internalCluster().getInstance(Exporters.class, nodeName);
        return (HttpExporter) exporters.iterator().next();
    }

    private MarvelDoc newRandomMarvelDoc() {
        if (randomBoolean()) {
            return new IndexRecoveryMarvelDoc(internalCluster().getClusterName(),
                    IndexRecoveryCollector.TYPE, System.currentTimeMillis(), new RecoveryResponse());
        } else {
            return new ClusterStateMarvelDoc(internalCluster().getClusterName(),
                    ClusterStateCollector.TYPE, System.currentTimeMillis(), ClusterState.PROTO, ClusterHealthStatus.GREEN);
        }
    }

    private void enqueueGetClusterVersionResponse(Version v) throws IOException {
        enqueueGetClusterVersionResponse(webServer, v);
    }

    private void enqueueGetClusterVersionResponse(MockWebServer mockWebServer, Version v) throws IOException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(jsonBuilder().startObject().startObject("version").field("number", v.number()).endObject().endObject().bytes().toUtf8()));
    }

    private void enqueueResponse(int responseCode, String body) throws IOException {
        enqueueResponse(webServer, responseCode, body);
    }

    private void enqueueResponse(MockWebServer mockWebServer, int responseCode, String body) throws IOException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(responseCode).setBody(body));
    }
}
