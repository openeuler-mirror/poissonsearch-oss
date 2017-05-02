/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.upgrades;

import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.Version;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.CheckedConsumer;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.test.rest.yaml.ObjectPath;
import org.elasticsearch.xpack.watcher.condition.AlwaysCondition;
import org.junit.Before;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.elasticsearch.xpack.security.SecurityLifecycleService.SECURITY_TEMPLATE_NAME;
import static org.elasticsearch.xpack.watcher.actions.ActionBuilders.loggingAction;
import static org.elasticsearch.xpack.watcher.client.WatchSourceBuilders.watchBuilder;
import static org.elasticsearch.xpack.watcher.input.InputBuilders.simpleInput;
import static org.elasticsearch.xpack.watcher.trigger.TriggerBuilders.schedule;
import static org.elasticsearch.xpack.watcher.trigger.schedule.Schedules.interval;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

public class WatchBackwardsCompatibilityIT extends ESRestTestCase {

    @Before
    public void waitForSecuritySetup() throws Exception {
        String masterNode = null;
        String catNodesResponse = EntityUtils.toString(
                client().performRequest("GET", "/_cat/nodes?h=id,master").getEntity(),
                StandardCharsets.UTF_8
        );
        for (String line : catNodesResponse.split("\n")) {
            int indexOfStar = line.indexOf('*'); // * in the node's output denotes it is master
            if (indexOfStar != -1) {
                masterNode = line.substring(0, indexOfStar).trim();
                break;
            }
        }
        assertNotNull(masterNode);
        final String masterNodeId = masterNode;

        assertBusy(() -> {
            try {
                Response nodeDetailsResponse = client().performRequest("GET", "/_nodes");
                ObjectPath path = ObjectPath.createFromResponse(nodeDetailsResponse);
                Map<String, Object> nodes = path.evaluate("nodes");
                assertThat(nodes.size(), greaterThanOrEqualTo(2));
                String masterVersion = null;
                for (String key : nodes.keySet()) {
                    // get the ES version number master is on
                    if (key.startsWith(masterNodeId)) {
                        masterVersion = path.evaluate("nodes." + key + ".version");
                        break;
                    }
                }
                assertNotNull(masterVersion);
                final String masterTemplateVersion = masterVersion;

                Response response = client().performRequest("GET", "/_cluster/state/metadata");
                ObjectPath objectPath = ObjectPath.createFromResponse(response);
                final String mappingsPath = "metadata.templates." + SECURITY_TEMPLATE_NAME + "" +
                        ".mappings";
                Map<String, Object> mappings = objectPath.evaluate(mappingsPath);
                assertNotNull(mappings);
                assertThat(mappings.size(), greaterThanOrEqualTo(1));
                for (String key : mappings.keySet()) {
                    String templateVersion = objectPath.evaluate(mappingsPath + "." + key + "" +
                            "._meta.security-version");
                    assertEquals(masterTemplateVersion, templateVersion);
                }
            } catch (Exception e) {
                throw new AssertionError("failed to get cluster state", e);
            }
        });
    }

    private Nodes nodes;

    @Before
    public void ensureNewNodesAreInCluster() throws IOException {
        nodes = buildNodeAndVersions();
        assumeFalse("new nodes is empty", nodes.getNewNodes().isEmpty());

    }

    public void testWatcherStats() throws Exception {
        executeAgainstAllNodes(client ->
            assertOK(client.performRequest("GET", "/_xpack/watcher/stats"))
        );
    }

    public void testWatcherRestart() throws Exception {
        executeAgainstAllNodes(client -> {
            assertOK(client.performRequest("POST", "/_xpack/watcher/_stop"));
            assertOK(client.performRequest("POST", "/_xpack/watcher/_start"));
        });
    }

    public void testWatchCrudApis() throws IOException {
        BytesReference bytesReference = watchBuilder()
                .trigger(schedule(interval("5m")))
                .input(simpleInput())
                .condition(AlwaysCondition.INSTANCE)
                .addAction("_action1", loggingAction("{{ctx.watch_id}}"))
                .buildAsBytes(XContentType.JSON);
        StringEntity entity = new StringEntity(bytesReference.utf8ToString(),
                ContentType.APPLICATION_JSON);

        executeAgainstAllNodes(client -> {
            assertOK(client.performRequest("PUT", "/_xpack/watcher/watch/my-watch",
                    Collections.emptyMap(), entity));

            assertOK(client.performRequest("GET", "/_xpack/watcher/watch/my-watch"));

            assertOK(client.performRequest("POST", "/_xpack/watcher/watch/my-watch/_execute"));

            assertOK(client.performRequest("PUT", "/_xpack/watcher/watch/my-watch/_deactivate"));

            assertOK(client.performRequest("PUT", "/_xpack/watcher/watch/my-watch/_activate"));
        });
    }

    public void executeAgainstAllNodes(CheckedConsumer<RestClient, IOException> consumer)
            throws IOException {
        try (RestClient newClient = buildClient(restClientSettings(),
                nodes.getNewNodes().stream().map(Node::getPublishAddress)
                        .toArray(HttpHost[]::new))) {
            consumer.accept(newClient);
        }

        try (RestClient bwcClient = buildClient(restClientSettings(),
                nodes.getBWCNodes().stream().map(Node::getPublishAddress)
                        .toArray(HttpHost[]::new))) {
            consumer.accept(bwcClient);
        }
    }

    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    @Override
    protected Settings restClientSettings() {
        String token = "Basic " + Base64.getEncoder()
                .encodeToString("elastic:changeme".getBytes(StandardCharsets.UTF_8));
        return Settings.builder()
                .put(ThreadContext.PREFIX + ".Authorization", token)
                .build();
    }

    private void assertOK(Response response) {
        assertThat(response.getStatusLine().getStatusCode(), anyOf(equalTo(200), equalTo(201)));
    }

    private Nodes buildNodeAndVersions() throws IOException {
        Response response = client().performRequest("GET", "_nodes");
        ObjectPath objectPath = ObjectPath.createFromResponse(response);
        Map<String, Object> nodesAsMap = objectPath.evaluate("nodes");
        Nodes nodes = new Nodes();
        for (String id : nodesAsMap.keySet()) {
            nodes.add(new Node(
                    id,
                    objectPath.evaluate("nodes." + id + ".name"),
                    Version.fromString(objectPath.evaluate("nodes." + id + ".version")),
                    HttpHost.create(objectPath.evaluate("nodes." + id + ".http.publish_address"))));
        }
        response = client().performRequest("GET", "_cluster/state");
        nodes.setMasterNodeId(ObjectPath.createFromResponse(response).evaluate("master_node"));
        return nodes;
    }

    final class Nodes extends HashMap<String, Node> {

        private String masterNodeId = null;

        public Node getMaster() {
            return get(masterNodeId);
        }

        public void setMasterNodeId(String id) {
            if (get(id) == null) {
                throw new IllegalArgumentException("node with id [" + id + "] not found. got:" +
                        toString());
            }
            masterNodeId = id;
        }

        public void add(Node node) {
            put(node.getId(), node);
        }

        public List<Node> getNewNodes() {
            Version bwcVersion = getBWCVersion();
            return values().stream().filter(n -> n.getVersion().after(bwcVersion))
                    .collect(Collectors.toList());
        }

        public List<Node> getBWCNodes() {
            Version bwcVersion = getBWCVersion();
            return values().stream().filter(n -> n.getVersion().equals(bwcVersion))
                    .collect(Collectors.toList());
        }

        public Version getBWCVersion() {
            if (isEmpty()) {
                throw new IllegalStateException("no nodes available");
            }
            return Version.fromId(values().stream().map(node -> node.getVersion().id)
                    .min(Integer::compareTo).get());
        }

        public Node getSafe(String id) {
            Node node = get(id);
            if (node == null) {
                throw new IllegalArgumentException("node with id [" + id + "] not found");
            }
            return node;
        }

        @Override
        public String toString() {
            return "Nodes{" +
                    "masterNodeId='" + masterNodeId + "'\n" +
                    values().stream().map(Node::toString).collect(Collectors.joining("\n")) +
                    '}';
        }
    }

    final class Node {
        private final String id;
        private final String nodeName;
        private final Version version;
        private final HttpHost publishAddress;

        Node(String id, String nodeName, Version version, HttpHost publishAddress) {
            this.id = id;
            this.nodeName = nodeName;
            this.version = version;
            this.publishAddress = publishAddress;
        }

        public String getId() {
            return id;
        }

        public String getNodeName() {
            return nodeName;
        }

        public HttpHost getPublishAddress() {
            return publishAddress;
        }

        public Version getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return "Node{" +
                    "id='" + id + '\'' +
                    ", nodeName='" + nodeName + '\'' +
                    ", version=" + version +
                    '}';
        }
    }
}
