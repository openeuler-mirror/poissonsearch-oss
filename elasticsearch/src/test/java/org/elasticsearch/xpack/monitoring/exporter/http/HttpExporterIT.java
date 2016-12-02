/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.exporter.http;

import com.squareup.okhttp.mockwebserver.MockResponse;
import com.squareup.okhttp.mockwebserver.MockWebServer;
import com.squareup.okhttp.mockwebserver.QueueDispatcher;
import com.squareup.okhttp.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.elasticsearch.Version;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.admin.indices.recovery.RecoveryResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.Requests;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.health.ClusterHealthStatus;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.Scope;
import org.elasticsearch.xpack.monitoring.MonitoredSystem;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.monitoring.collector.cluster.ClusterStateMonitoringDoc;
import org.elasticsearch.xpack.monitoring.collector.indices.IndexRecoveryMonitoringDoc;
import org.elasticsearch.xpack.monitoring.exporter.Exporter;
import org.elasticsearch.xpack.monitoring.exporter.Exporters;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringDoc;
import org.elasticsearch.xpack.monitoring.exporter.MonitoringTemplateUtils;
import org.elasticsearch.xpack.monitoring.resolver.ResolversRegistry;
import org.elasticsearch.xpack.monitoring.resolver.bulk.MonitoringBulkTimestampedResolver;
import org.elasticsearch.xpack.monitoring.test.MonitoringIntegTestCase;
import org.joda.time.format.DateTimeFormat;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.xpack.monitoring.exporter.http.PublishableHttpResource.FILTER_PATH_NONE;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@ESIntegTestCase.ClusterScope(scope = Scope.TEST, numDataNodes = 0, numClientNodes = 0, transportClientRatio = 0.0)
public class HttpExporterIT extends MonitoringIntegTestCase {

    private MockWebServer webServer;

    @Before
    public void startWebServer() throws IOException {
        webServer = createMockWebServer();
    }

    @After
    public void stopWebServer() throws Exception {
        webServer.shutdown();
    }

    @Override
    protected boolean ignoreExternalCluster() {
        return true;
    }

    public void testExport() throws Exception {
        final boolean templatesExistsAlready = randomBoolean();
        final boolean pipelineExistsAlready = randomBoolean();
        final boolean bwcIndexesExist = randomBoolean();
        final boolean bwcAliasesExist = randomBoolean();

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueSetupResponses(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        enqueueResponse(200, "{\"errors\": false, \"msg\": \"successful bulk request\"}");

        final Settings.Builder builder = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.exporters._http.type", "http")
                .put("xpack.monitoring.exporters._http.host", getFormattedAddress(webServer));

        internalCluster().startNode(builder);

        final int nbDocs = randomIntBetween(1, 25);
        export(newRandomMonitoringDocs(nbDocs));

        assertMonitorResources(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        assertBulk(webServer, nbDocs);
    }

    public void testExportWithHeaders() throws Exception {
        final boolean templatesExistsAlready = randomBoolean();
        final boolean pipelineExistsAlready = randomBoolean();
        final boolean bwcIndexesExist = randomBoolean();
        final boolean bwcAliasesExist = randomBoolean();

        final String headerValue = randomAsciiOfLengthBetween(3, 9);
        final String[] array = generateRandomStringArray(2, 4, false);

        final Map<String, String[]> headers = new HashMap<>();

        headers.put("X-Cloud-Cluster", new String[] { headerValue });
        headers.put("X-Found-Cluster", new String[] { headerValue });
        headers.put("Array-Check", array);

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueSetupResponses(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        enqueueResponse(200, "{\"errors\": false, \"msg\": \"successful bulk request\"}");

        Settings.Builder builder = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.exporters._http.type", "http")
                .put("xpack.monitoring.exporters._http.host", getFormattedAddress(webServer))
                .put("xpack.monitoring.exporters._http.headers.X-Cloud-Cluster", headerValue)
                .put("xpack.monitoring.exporters._http.headers.X-Found-Cluster", headerValue)
                .putArray("xpack.monitoring.exporters._http.headers.Array-Check", array);

        internalCluster().startNode(builder);

        final int nbDocs = randomIntBetween(1, 25);
        export(newRandomMonitoringDocs(nbDocs));

        assertMonitorResources(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist, headers, null);
        assertBulk(webServer, nbDocs, headers, null);
    }

    public void testExportWithBasePath() throws Exception {
        final boolean useHeaders = randomBoolean();
        final boolean templatesExistsAlready = randomBoolean();
        final boolean pipelineExistsAlready = randomBoolean();
        final boolean bwcIndexesExist = randomBoolean();
        final boolean bwcAliasesExist = randomBoolean();

        final String headerValue = randomAsciiOfLengthBetween(3, 9);
        final String[] array = generateRandomStringArray(2, 4, false);

        final Map<String, String[]> headers = new HashMap<>();

        if (useHeaders) {
            headers.put("X-Cloud-Cluster", new String[] { headerValue });
            headers.put("X-Found-Cluster", new String[] { headerValue });
            headers.put("Array-Check", array);
        }

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueSetupResponses(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        enqueueResponse(200, "{\"errors\": false}");

        String basePath = "path/to";

        if (randomBoolean()) {
            basePath += "/something";

            if (rarely()) {
                basePath += "/proxied";
            }
        }

        if (randomBoolean()) {
            basePath = "/" + basePath;
        }

        final Settings.Builder builder = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.exporters._http.type", "http")
                .put("xpack.monitoring.exporters._http.host", getFormattedAddress(webServer))
                .put("xpack.monitoring.exporters._http.proxy.base_path", basePath + (randomBoolean() ? "/" : ""));

        if (useHeaders) {
            builder.put("xpack.monitoring.exporters._http.headers.X-Cloud-Cluster", headerValue)
                    .put("xpack.monitoring.exporters._http.headers.X-Found-Cluster", headerValue)
                    .putArray("xpack.monitoring.exporters._http.headers.Array-Check", array);
        }

        internalCluster().startNode(builder);

        final int nbDocs = randomIntBetween(1, 25);
        export(newRandomMonitoringDocs(nbDocs));

        assertMonitorResources(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist, headers,
                basePath);
        assertBulk(webServer, nbDocs, headers, basePath);
    }

    public void testHostChangeReChecksTemplate() throws Exception {
        final boolean templatesExistsAlready = randomBoolean();
        final boolean pipelineExistsAlready = randomBoolean();
        final boolean bwcIndexesExist = randomBoolean();
        final boolean bwcAliasesExist = randomBoolean();

        Settings.Builder builder = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.exporters._http.type", "http")
                .put("xpack.monitoring.exporters._http.host", getFormattedAddress(webServer));

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueSetupResponses(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        enqueueResponse(200, "{\"errors\": false}");

        internalCluster().startNode(builder);

        export(Collections.singletonList(newRandomMonitoringDoc()));

        assertMonitorResources(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        assertBulk(webServer);

        final MockWebServer secondWebServer = createMockWebServer();
        try {
            assertAcked(client().admin().cluster().prepareUpdateSettings().setTransientSettings(
                    Settings.builder().putArray("xpack.monitoring.exporters._http.host", getFormattedAddress(secondWebServer))));

            enqueueGetClusterVersionResponse(secondWebServer, Version.CURRENT);
            // pretend that one of the templates is missing
            for (Tuple<String, String> template : monitoringTemplates()) {
                if (template.v1().contains(MonitoringBulkTimestampedResolver.Data.DATA)) {
                    enqueueResponse(secondWebServer, 200, "template [" + template + "] exists");
                } else {
                    enqueueResponse(secondWebServer, 404, "template [" + template + "] does not exist");
                    enqueueResponse(secondWebServer, 201, "template [" + template + "] created");
                }
            }
            // opposite of if it existed before
            enqueuePipelineResponses(secondWebServer, !pipelineExistsAlready);
            enqueueBackwardsCompatibilityAliasResponse(secondWebServer, bwcIndexesExist, true);
            enqueueResponse(secondWebServer, 200, "{\"errors\": false}");

            logger.info("--> exporting a second event");
            export(Collections.singletonList(newRandomMonitoringDoc()));

            assertMonitorVersion(secondWebServer);

            for (Tuple<String, String> template : monitoringTemplates()) {
                RecordedRequest recordedRequest = secondWebServer.takeRequest();
                assertThat(recordedRequest.getMethod(), equalTo("GET"));
                assertThat(recordedRequest.getPath(), equalTo("/_template/" + template.v1() + resourceQueryString()));

                if (template.v1().contains(MonitoringBulkTimestampedResolver.Data.DATA) == false) {
                    recordedRequest = secondWebServer.takeRequest();
                    assertThat(recordedRequest.getMethod(), equalTo("PUT"));
                    assertThat(recordedRequest.getPath(), equalTo("/_template/" + template.v1() + resourceQueryString()));
                    assertThat(recordedRequest.getBody().readUtf8(), equalTo(template.v2()));
                }
            }
            assertMonitorPipelines(secondWebServer, !pipelineExistsAlready, null, null);
            assertMonitorBackwardsCompatibilityAliases(secondWebServer, false, null, null);
            assertBulk(secondWebServer);
        } finally {
            secondWebServer.shutdown();
        }
    }

    public void testUnsupportedClusterVersion() throws Exception {
        Settings.Builder builder = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.exporters._http.type", "http")
                .put("xpack.monitoring.exporters._http.host", getFormattedAddress(webServer));

        // returning an unsupported cluster version
        enqueueGetClusterVersionResponse(randomFrom(Version.fromString("0.18.0"), Version.fromString("1.0.0"), Version.fromString("1.4.0"),
                Version.fromString("2.4.0")));

        String agentNode = internalCluster().startNode(builder);

        // fire off what should be an unsuccessful request
        assertNull(getExporter(agentNode).openBulk());

        assertThat(webServer.getRequestCount(), equalTo(1));

        assertMonitorVersion(webServer);
    }

    public void testDynamicIndexFormatChange() throws Exception {
        final boolean templatesExistsAlready = randomBoolean();
        final boolean pipelineExistsAlready = randomBoolean();
        final boolean bwcIndexesExist = randomBoolean();
        final boolean bwcAliasesExist = randomBoolean();

        Settings.Builder builder = Settings.builder().put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.exporters._http.type", "http")
                .put("xpack.monitoring.exporters._http.host", getFormattedAddress(webServer));

        internalCluster().startNode(builder);

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueSetupResponses(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        enqueueResponse(200, "{\"errors\": false, \"msg\": \"successful bulk request\"}");

        MonitoringDoc doc = newRandomMonitoringDoc();
        export(Collections.singletonList(doc));

        assertMonitorResources(webServer, templatesExistsAlready, pipelineExistsAlready, bwcIndexesExist, bwcAliasesExist);
        RecordedRequest recordedRequest = assertBulk(webServer);

        @SuppressWarnings("unchecked")
        String indexName = new ResolversRegistry(Settings.EMPTY).getResolver(doc).index(doc);

        byte[] bytes = recordedRequest.getBody().readByteArray();
        Map<String, Object> data = XContentHelper.convertToMap(new BytesArray(bytes), false).v2();
        @SuppressWarnings("unchecked")
        Map<String, Object> index = (Map<String, Object>) data.get("index");
        assertThat(index.get("_index"), equalTo(indexName));

        String newTimeFormat = randomFrom("YY", "YYYY", "YYYY.MM", "YYYY-MM", "MM.YYYY", "MM");
        assertAcked(client().admin().cluster().prepareUpdateSettings()
                .setTransientSettings(Settings.builder().put("xpack.monitoring.exporters._http.index.name.time_format", newTimeFormat)));

        enqueueGetClusterVersionResponse(Version.CURRENT);
        enqueueSetupResponses(webServer, true, true, false, false);
        enqueueResponse(200, "{\"errors\": false, \"msg\": \"successful bulk request\"}");

        doc = newRandomMonitoringDoc();
        export(Collections.singletonList(doc));

        String expectedMonitoringIndex = ".monitoring-es-" + MonitoringTemplateUtils.TEMPLATE_VERSION + "-"
                + DateTimeFormat.forPattern(newTimeFormat).withZoneUTC().print(doc.getTimestamp());

        assertMonitorResources(webServer, true, true, false, false);
        recordedRequest = assertBulk(webServer);

        bytes = recordedRequest.getBody().readByteArray();
        data = XContentHelper.convertToMap(new BytesArray(bytes), false).v2();
        @SuppressWarnings("unchecked")
        final Map<String, Object> newIndex = (Map<String, Object>) data.get("index");
        assertThat(newIndex.get("_index"), equalTo(expectedMonitoringIndex));
    }

    private void assertMonitorVersion(final MockWebServer webServer) throws Exception {
        assertMonitorVersion(webServer, null, null);
    }

    private void assertMonitorVersion(final MockWebServer webServer, @Nullable final Map<String, String[]> customHeaders,
            @Nullable final String basePath) throws Exception {
        final String pathPrefix = basePathToAssertablePrefix(basePath);
        final RecordedRequest request = webServer.takeRequest();

        assertThat(request.getMethod(), equalTo("GET"));
        assertThat(request.getPath(), equalTo(pathPrefix + "/?filter_path=version.number"));
        assertHeaders(request, customHeaders);
    }

    private void assertMonitorResources(final MockWebServer webServer, final boolean templateAlreadyExists,
            final boolean pipelineAlreadyExists, boolean bwcIndexesExist, boolean bwcAliasesExist) throws Exception {
        assertMonitorResources(webServer, templateAlreadyExists, pipelineAlreadyExists, bwcIndexesExist, bwcAliasesExist, null, null);
    }

    private void assertMonitorResources(final MockWebServer webServer, final boolean templateAlreadyExists,
            final boolean pipelineAlreadyExists, boolean bwcIndexesExist, boolean bwcAliasesExist,
            @Nullable final Map<String, String[]> customHeaders, @Nullable final String basePath) throws Exception {
        assertMonitorVersion(webServer, customHeaders, basePath);
        assertMonitorTemplates(webServer, templateAlreadyExists, customHeaders, basePath);
        assertMonitorPipelines(webServer, pipelineAlreadyExists, customHeaders, basePath);
        assertMonitorBackwardsCompatibilityAliases(webServer, bwcIndexesExist && false == bwcAliasesExist, customHeaders, basePath);
    }

    private void assertMonitorTemplates(final MockWebServer webServer, final boolean alreadyExists,
            @Nullable final Map<String, String[]> customHeaders, @Nullable final String basePath) throws Exception {
        final String pathPrefix = basePathToAssertablePrefix(basePath);
        RecordedRequest request;

        for (Tuple<String, String> template : monitoringTemplates()) {
            request = webServer.takeRequest();

            assertThat(request.getMethod(), equalTo("GET"));
            assertThat(request.getPath(), equalTo(pathPrefix + "/_template/" + template.v1() + resourceQueryString()));
            assertHeaders(request, customHeaders);

            if (alreadyExists == false) {
                request = webServer.takeRequest();

                assertThat(request.getMethod(), equalTo("PUT"));
                assertThat(request.getPath(), equalTo(pathPrefix + "/_template/" + template.v1() + resourceQueryString()));
                assertThat(request.getBody().readUtf8(), equalTo(template.v2()));
                assertHeaders(request, customHeaders);
            }
        }
    }

    private void assertMonitorPipelines(final MockWebServer webServer, final boolean alreadyExists,
            @Nullable final Map<String, String[]> customHeaders, @Nullable final String basePath) throws Exception {
        final String pathPrefix = basePathToAssertablePrefix(basePath);
        RecordedRequest request = webServer.takeRequest();

        assertThat(request.getMethod(), equalTo("GET"));
        assertThat(request.getPath(), equalTo(pathPrefix + "/_ingest/pipeline/" + Exporter.EXPORT_PIPELINE_NAME + resourceQueryString()));
        assertHeaders(request, customHeaders);

        if (alreadyExists == false) {
            request = webServer.takeRequest();

            assertThat(request.getMethod(), equalTo("PUT"));
            assertThat(request.getPath(),
                    equalTo(pathPrefix + "/_ingest/pipeline/" + Exporter.EXPORT_PIPELINE_NAME + resourceQueryString()));
            assertThat(request.getBody().readUtf8(), equalTo(Exporter.emptyPipeline(XContentType.JSON).string()));
            assertHeaders(request, customHeaders);
        }
    }

    private void assertMonitorBackwardsCompatibilityAliases(final MockWebServer webServer, final boolean expectPost,
            @Nullable final Map<String, String[]> customHeaders, @Nullable final String basePath) throws Exception {
        final String pathPrefix = basePathToAssertablePrefix(basePath);
        RecordedRequest request = webServer.takeRequest();

        assertThat(request.getMethod(), equalTo("GET"));
        assertThat(request.getPath(), startsWith(pathPrefix + "/.marvel-es-1-*"));
        assertThat(request.getPath(), containsString("filter_path=*.aliases"));
        assertHeaders(request, customHeaders);

        if (expectPost) {
            request = webServer.takeRequest();

            assertThat(request.getMethod(), equalTo("POST"));
            assertThat(request.getPath(), startsWith(pathPrefix + "/_aliases"));
            assertThat(request.getPath(), containsString("master_timeout=30s"));
            assertThat(request.getBody().readUtf8(), containsString("add"));
            assertHeaders(request, customHeaders);
        }

    }

    private RecordedRequest assertBulk(final MockWebServer webServer) throws Exception {
        return assertBulk(webServer, -1);
    }

    private RecordedRequest assertBulk(final MockWebServer webServer, final int docs) throws Exception {
        return assertBulk(webServer, docs, null, null);
    }

    private RecordedRequest assertBulk(final MockWebServer webServer, final int docs, @Nullable final Map<String, String[]> customHeaders,
            @Nullable final String basePath) throws Exception {
        final String pathPrefix = basePathToAssertablePrefix(basePath);
        final RecordedRequest request = webServer.takeRequest();

        assertThat(request.getMethod(), equalTo("POST"));
        assertThat(request.getPath(), equalTo(pathPrefix + "/_bulk" + bulkQueryString()));
        assertHeaders(request, customHeaders);

        if (docs != -1) {
            assertBulkRequest(request.getBody(), docs);
        }

        return request;
    }

    private void assertHeaders(final RecordedRequest request, final Map<String, String[]> customHeaders) {
        if (customHeaders != null) {
            for (final Map.Entry<String, String[]> entry : customHeaders.entrySet()) {
                final String header = entry.getKey();
                final String[] values = entry.getValue();

                final List<String> headerValues = request.getHeaders().values(header);

                assertThat(header, headerValues, hasSize(values.length));
                assertThat(header, headerValues, containsInAnyOrder(values));
            }
        }
    }

    private void export(Collection<MonitoringDoc> docs) throws Exception {
        Exporters exporters = internalCluster().getInstance(Exporters.class);
        assertThat(exporters, notNullValue());

        // Wait for exporting bulks to be ready to export
        assertBusy(() -> exporters.forEach(exporter -> assertThat(exporter.openBulk(), notNullValue())));
        exporters.export(docs);
    }

    private HttpExporter getExporter(String nodeName) {
        Exporters exporters = internalCluster().getInstance(Exporters.class, nodeName);
        return (HttpExporter) exporters.iterator().next();
    }

    private MonitoringDoc newRandomMonitoringDoc() {
        if (randomBoolean()) {
            IndexRecoveryMonitoringDoc doc = new IndexRecoveryMonitoringDoc(MonitoredSystem.ES.getSystem(), Version.CURRENT.toString());
            doc.setClusterUUID(internalCluster().getClusterName());
            doc.setTimestamp(System.currentTimeMillis());
            doc.setSourceNode(new DiscoveryNode("id", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT));
            doc.setRecoveryResponse(new RecoveryResponse());
            return doc;
        } else {
            ClusterStateMonitoringDoc doc = new ClusterStateMonitoringDoc(MonitoredSystem.ES.getSystem(), Version.CURRENT.toString());
            doc.setClusterUUID(internalCluster().getClusterName());
            doc.setTimestamp(System.currentTimeMillis());
            doc.setSourceNode(new DiscoveryNode("id", buildNewFakeTransportAddress(), emptyMap(), emptySet(), Version.CURRENT));
            doc.setClusterState(ClusterState.PROTO);
            doc.setStatus(ClusterHealthStatus.GREEN);
            return doc;
        }
    }

    private List<MonitoringDoc> newRandomMonitoringDocs(int nb) {
        List<MonitoringDoc> docs = new ArrayList<>(nb);
        for (int i = 0; i < nb; i++) {
            docs.add(newRandomMonitoringDoc());
        }
        return docs;
    }

    private String basePathToAssertablePrefix(@Nullable final String basePath) {
        if (basePath == null) {
            return "";
        }

        return basePath.startsWith("/") == false ? "/" + basePath : basePath;
    }

    private String resourceQueryString() {
        return "?filter_path=" + urlEncode(FILTER_PATH_NONE);
    }

    private String bulkQueryString() {
        return "?pipeline=" + urlEncode(Exporter.EXPORT_PIPELINE_NAME) + "&filter_path=" + urlEncode("errors,items.*.error");
    }

    private String urlEncode(final String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // whelp, our JVM is broken
            throw new RuntimeException(e);
        }
    }

    private void enqueueGetClusterVersionResponse(Version v) throws IOException {
        enqueueGetClusterVersionResponse(webServer, v);
    }

    private void enqueueGetClusterVersionResponse(MockWebServer mockWebServer, Version v) throws IOException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setBody(jsonBuilder().startObject().startObject("version")
                .field("number", v.toString()).endObject().endObject().bytes().utf8ToString()));
    }

    private void enqueueSetupResponses(MockWebServer webServer, boolean templatesAlreadyExists, boolean pipelineAlreadyExists,
            boolean bwcIndexesExist, boolean bwcAliasesExist) throws IOException {
        enqueueTemplateResponses(webServer, templatesAlreadyExists);
        enqueuePipelineResponses(webServer, pipelineAlreadyExists);
        enqueueBackwardsCompatibilityAliasResponse(webServer, bwcIndexesExist, bwcAliasesExist);
    }

    private void enqueueTemplateResponses(final MockWebServer webServer, final boolean alreadyExists) throws IOException {
        if (alreadyExists) {
            enqueueTemplateResponsesExistsAlready(webServer);
        } else {
            enqueueTemplateResponsesDoesNotExistYet(webServer);
        }
    }

    private void enqueueTemplateResponsesDoesNotExistYet(final MockWebServer webServer) throws IOException {
        for (String template : monitoringTemplateNames()) {
            enqueueResponse(webServer, 404, "template [" + template + "] does not exist");
            enqueueResponse(webServer, 201, "template [" + template + "] created");
        }
    }

    private void enqueueTemplateResponsesExistsAlready(final MockWebServer webServer) throws IOException {
        for (String template : monitoringTemplateNames()) {
            enqueueResponse(webServer, 200, "template [" + template + "] exists");
        }
    }

    private void enqueuePipelineResponses(final MockWebServer webServer, final boolean alreadyExists) throws IOException {
        if (alreadyExists) {
            enqueuePipelineResponsesExistsAlready(webServer);
        } else {
            enqueuePipelineResponsesDoesNotExistYet(webServer);
        }
    }

    private void enqueuePipelineResponsesDoesNotExistYet(final MockWebServer webServer) throws IOException {
        enqueueResponse(webServer, 404, "pipeline [" + Exporter.EXPORT_PIPELINE_NAME + "] does not exist");
        enqueueResponse(webServer, 201, "pipeline [" + Exporter.EXPORT_PIPELINE_NAME + "] created");
    }

    private void enqueuePipelineResponsesExistsAlready(final MockWebServer webServer) throws IOException {
        enqueueResponse(webServer, 200, "pipeline [" + Exporter.EXPORT_PIPELINE_NAME + "] exists");
    }

    private void enqueueBackwardsCompatibilityAliasResponse(MockWebServer webServer, boolean bwcIndexesExist, boolean bwcAliasesExist)
            throws IOException {
        if (false == bwcIndexesExist && randomBoolean()) {
            enqueueResponse(webServer, 404, "index does not exist");
            return;
        }
        XContentBuilder response = JsonXContent.contentBuilder().prettyPrint().startObject();
        if (bwcIndexesExist) {
            int timestampIndexes = between(1, 100);
            for (int i = 0; i < timestampIndexes; i++) {
                writeIndex(response, ".marvel-es-1-" + i, bwcAliasesExist ? ".monitoring-es-2-" + i + "-alias" : "ignored");
            }
        }
        response.endObject();
        enqueueResponse(webServer, 200, response.string());
        if (bwcIndexesExist && false == bwcAliasesExist) {
            enqueueResponse(webServer, 200, "{\"acknowledged\": true}");
        }
    }

    private void writeIndex(XContentBuilder response, String index, String alias) throws IOException {
        response.startObject(index);
        {
            response.startObject("aliases");
            {
                response.startObject(alias).endObject();
            }
            response.endObject();
        }
        response.endObject();
    }

    private void enqueueResponse(int responseCode, String body) throws IOException {
        enqueueResponse(webServer, responseCode, body);
    }

    private void enqueueResponse(MockWebServer mockWebServer, int responseCode, String body) throws IOException {
        mockWebServer.enqueue(new MockResponse().setResponseCode(responseCode).setBody(body));
    }

    private void assertBulkRequest(Buffer requestBody, int numberOfActions) throws Exception {
        BulkRequest bulkRequest = Requests.bulkRequest().add(new BytesArray(requestBody.readByteArray()), null, null);
        assertThat(bulkRequest.numberOfActions(), equalTo(numberOfActions));
        for (DocWriteRequest actionRequest : bulkRequest.requests()) {
            assertThat(actionRequest, instanceOf(IndexRequest.class));
        }
    }

    private String getFormattedAddress(MockWebServer server) {
        return server.getHostName() + ":" + server.getPort();
    }

    private MockWebServer createMockWebServer() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        final QueueDispatcher dispatcher = new QueueDispatcher();
        dispatcher.setFailFast(true);
        server.setDispatcher(dispatcher);
        return server;
    }
}
