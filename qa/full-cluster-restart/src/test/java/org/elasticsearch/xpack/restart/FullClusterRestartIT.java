/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.restart;

import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.Version;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.NotEqualMessageBuilder;
import org.elasticsearch.test.StreamsUtils;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xpack.common.text.TextTemplate;
import org.elasticsearch.xpack.security.SecurityClusterClientYamlTestCase;
import org.elasticsearch.xpack.test.rest.XPackRestTestCase;
import org.elasticsearch.xpack.watcher.actions.logging.LoggingAction;
import org.elasticsearch.xpack.watcher.client.WatchSourceBuilder;
import org.elasticsearch.xpack.watcher.condition.AlwaysCondition;
import org.elasticsearch.xpack.watcher.support.xcontent.ObjectPath;
import org.elasticsearch.xpack.watcher.trigger.schedule.IntervalSchedule;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleTrigger;
import org.junit.Before;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;
import static org.elasticsearch.common.unit.TimeValue.timeValueSeconds;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

public class FullClusterRestartIT extends ESRestTestCase {
    private final boolean runningAgainstOldCluster = Booleans.parseBoolean(System.getProperty("tests.is_old_cluster"));
    private final Version oldClusterVersion = Version.fromString(System.getProperty("tests.old_cluster_version"));

    @Before
    public void waitForSecuritySetup() throws Exception {
        SecurityClusterClientYamlTestCase.waitForSecurity();
    }

    @Before
    public void waitForMlTemplates() throws Exception {
        XPackRestTestCase.waitForMlTemplates();
    }

    @Override
    protected boolean preserveIndicesUponCompletion() {
        return true;
    }

    @Override
    protected boolean preserveTemplatesUponCompletion() {
        return true;
    }

    @Override
    protected Settings restClientSettings() {
        String token = "Basic " + Base64.getEncoder().encodeToString("elastic:changeme".getBytes(StandardCharsets.UTF_8));
        return Settings.builder()
                .put(ThreadContext.PREFIX + ".Authorization", token)
                // we increase the timeout here to 90 seconds to handle long waits for a green
                // cluster health. the waits for green need to be longer than a minute to
                // account for delayed shards
                .put(ESRestTestCase.CLIENT_RETRY_TIMEOUT, "90s")
                .put(ESRestTestCase.CLIENT_SOCKET_TIMEOUT, "90s")
                .build();
    }

    /**
     * Tests that a single document survives. Super basic smoke test.
     */
    public void testSingleDoc() throws IOException {
        String docLocation = "/testsingledoc/doc/1";
        String doc = "{\"test\": \"test\"}";

        if (runningAgainstOldCluster) {
            client().performRequest("PUT", docLocation, singletonMap("refresh", "true"),
                    new StringEntity(doc, ContentType.APPLICATION_JSON));
        }

        assertThat(toStr(client().performRequest("GET", docLocation)), containsString(doc));
    }

    // This will only work when the upgrade API is in place!
    @AwaitsFix(bugUrl = "https://github.com/elastic/dev/issues/741")
    public void testSecurityNativeRealm() throws IOException {
        XContentBuilder userBuilder = JsonXContent.contentBuilder().startObject();
        userBuilder.field("password", "j@rV1s");
        userBuilder.array("roles", "admin", "other_role1");
        userBuilder.field("full_name", "Jack Nicholson");
        userBuilder.field("email", "jacknich@example.com");
        userBuilder.startObject("metadata"); {
            userBuilder.field("intelligence", 7);
        }
        userBuilder.endObject();
        userBuilder.field("enabled", true);
        String user = userBuilder.endObject().string();

        if (runningAgainstOldCluster) {
            client().performRequest("PUT", "/_xpack/security/user/jacknich", emptyMap(),
                    new StringEntity(user, ContentType.APPLICATION_JSON));
        }

        Map<String, Object> response = toMap(client().performRequest("GET", "/_xpack/security/user/jacknich"));
        Map<String, Object> expected = toMap(user);
        expected.put("username", "jacknich");
        expected.remove("password");
        expected = singletonMap("jacknich", expected);
        if (false == response.equals(expected)) {
            NotEqualMessageBuilder message = new NotEqualMessageBuilder();
            message.compareMaps(response, expected);
            fail("User doesn't match.\n" + message.toString());
        }
    }

    public void testWatcher() throws Exception {
        if (runningAgainstOldCluster) {
            logger.info("Adding a watch on old cluster");
            client().performRequest("PUT", "_xpack/watcher/watch/bwc_watch", emptyMap(),
                    new StringEntity(loadWatch("simple-watch.json"), ContentType.APPLICATION_JSON));

            logger.info("Adding a watch with \"fun\" throttle periods on old cluster");
            client().performRequest("PUT", "_xpack/watcher/watch/bwc_throttle_period", emptyMap(),
                    new StringEntity(loadWatch("throttle-period-watch.json"), ContentType.APPLICATION_JSON));

            logger.info("Adding a watch with \"fun\" read timeout on old cluster");
            client().performRequest("PUT", "_xpack/watcher/watch/bwc_funny_timeout", emptyMap(),
                    new StringEntity(loadWatch("funny-timeout-watch.json"), ContentType.APPLICATION_JSON));

            logger.info("Waiting for watch results index to fill up...");
            waitForYellow(".watches,bwc_watch_index,.watcher-history*");
            waitForHits("bwc_watch_index", 2);
            waitForHits(".watcher-history*", 2);
            logger.info("Done creating watcher-related indices");
        } else {
            logger.info("testing against {}", oldClusterVersion);
            if (oldClusterVersion.before(Version.V_5_6_0)) {
                waitForYellow(".watches,bwc_watch_index,.watcher-history*");

                logger.info("checking that upgrade procedure on the new cluster is required");
                Map<String, Object> response = toMap(client().performRequest("GET", "/_xpack/migration/assistance"));
                logger.info(response);

                @SuppressWarnings("unchecked") Map<String, Object> indices = (Map<String, Object>) response.get("indices");
                assertThat(indices.entrySet(), hasSize(1));
                assertThat(indices.get(".watches"), notNullValue());
                @SuppressWarnings("unchecked") Map<String, Object> index = (Map<String, Object>) indices.get(".watches");
                assertThat(index.get("action_required"), equalTo("upgrade"));

                logger.info("starting upgrade procedure on the new cluster");

                Map<String, Object> upgradeResponse = toMap(client().performRequest("POST", "_xpack/migration/upgrade/.watches"));
                assertThat(upgradeResponse.get("timed_out"), equalTo(Boolean.FALSE));
                // we posted 3 watches, but monitoring can post a few more
                assertThat((int)upgradeResponse.get("total"), greaterThanOrEqualTo(3));

                logger.info("checking that upgrade procedure on the new cluster is required again");
                Map<String, Object> responseAfter = toMap(client().performRequest("GET", "/_xpack/migration/assistance"));
                @SuppressWarnings("unchecked") Map<String, Object> indicesAfter = (Map<String, Object>) responseAfter.get("indices");
                assertThat(indicesAfter.entrySet(), empty());

                // Wait for watcher to actually start....
                Map<String, Object> startWatchResponse = toMap(client().performRequest("POST", "_xpack/watcher/_start"));
                assertThat(startWatchResponse.get("acknowledged"), equalTo(Boolean.TRUE));
                assertBusy(() -> {
                    Map<String, Object> statsWatchResponse = toMap(client().performRequest("GET", "_xpack/watcher/stats"));
                    @SuppressWarnings("unchecked")
                    List<Object> states = ((List<Object>) statsWatchResponse.get("stats"))
                            .stream().map(o -> ((Map<String, Object>) o).get("watcher_state")).collect(Collectors.toList());
                    assertThat(states, everyItem(is("started")));
                });

                try {
                    assertOldTemplatesAreDeleted();
                    assertWatchIndexContentsWork();
                    assertBasicWatchInteractions();
                } finally {
                    /* Shut down watcher after every test because watcher can be a bit finicky about shutting down when the node shuts
                     * down. This makes super sure it shuts down *and* causes the test to fail in a sensible spot if it doesn't shut down.
                     */
                    Map<String, Object> stopWatchResponse = toMap(client().performRequest("POST", "_xpack/watcher/_stop"));
                    assertThat(stopWatchResponse.get("acknowledged"), equalTo(Boolean.TRUE));
                    assertBusy(() -> {
                        Map<String, Object> statsStoppedWatchResponse = toMap(client().performRequest("GET", "_xpack/watcher/stats"));
                        @SuppressWarnings("unchecked")
                        List<Object> states = ((List<Object>) statsStoppedWatchResponse.get("stats"))
                                .stream().map(o -> ((Map<String, Object>) o).get("watcher_state")).collect(Collectors.toList());
                        assertThat(states, everyItem(is("stopped")));
                    });
                }
            } else {
                // TODO: remove when 5.6 is fixed
                logger.info("Skipping 5.6.0 for now");
            }
        }
    }

    private String loadWatch(String watch) throws IOException {
        return StreamsUtils.copyToStringFromClasspath("/org/elasticsearch/xpack/restart/" + watch);
    }

    @SuppressWarnings("unchecked")
    private void assertOldTemplatesAreDeleted() throws IOException {
        Map<String, Object> templates = toMap(client().performRequest("GET", "/_template"));
        assertThat(templates.keySet(), not(hasItems(is("watches"), startsWith("watch-history"), is("triggered_watches"))));
    }

    @SuppressWarnings("unchecked")
    private void assertWatchIndexContentsWork() throws Exception {
        // Fetch a basic watch
        Map<String, Object> bwcWatch = toMap(client().performRequest("GET", "_xpack/watcher/watch/bwc_watch"));

        logger.error("-----> {}", bwcWatch);

        assertThat(bwcWatch.get("found"), equalTo(true));
        Map<String, Object> source = (Map<String, Object>) bwcWatch.get("watch");
        assertEquals(1000, source.get("throttle_period_in_millis"));
        int timeout = (int) timeValueSeconds(100).millis();
        assertThat(ObjectPath.eval("input.search.timeout_in_millis", source), equalTo(timeout));
        assertThat(ObjectPath.eval("actions.index_payload.transform.search.timeout_in_millis", source), equalTo(timeout));
        assertThat(ObjectPath.eval("actions.index_payload.index.index", source), equalTo("bwc_watch_index"));
        assertThat(ObjectPath.eval("actions.index_payload.index.doc_type", source), equalTo("bwc_watch_type"));
        assertThat(ObjectPath.eval("actions.index_payload.index.timeout_in_millis", source), equalTo(timeout));

        // Fetch a watch with "fun" throttle periods
        bwcWatch = toMap(client().performRequest("GET", "_xpack/watcher/watch/bwc_throttle_period"));
        assertThat(bwcWatch.get("found"), equalTo(true));
        source = (Map<String, Object>) bwcWatch.get("watch");
        assertEquals(timeout, source.get("throttle_period_in_millis"));
        assertThat(ObjectPath.eval("actions.index_payload.throttle_period_in_millis", source), equalTo(timeout));

        /*
         * Fetch a watch with a funny timeout to verify loading fractional time
         * values.
         */
        bwcWatch = toMap(client().performRequest("GET", "_xpack/watcher/watch/bwc_funny_timeout"));
        assertThat(bwcWatch.get("found"), equalTo(true));
        source = (Map<String, Object>) bwcWatch.get("watch");


        Map<String, Object> attachments = ObjectPath.eval("actions.work.email.attachments", source);
        Map<String, Object> attachment = (Map<String, Object>) attachments.get("test_report.pdf");
        Map<String, Object>  request =  ObjectPath.eval("http.request", attachment);
        assertEquals(timeout, request.get("read_timeout_millis"));
        assertEquals("https", request.get("scheme"));
        assertEquals("example.com", request.get("host"));
        assertEquals("{{ctx.metadata.report_url}}", request.get("path"));
        assertEquals(8443, request.get("port"));
        Map<?, ?> basic = ObjectPath.eval("auth.basic", request);
        assertThat(basic, hasEntry("username", "Aladdin"));
        // password doesn't come back because it is hidden
        assertThat(basic, not(hasKey("password")));

        Map<String, Object> history = toMap(client().performRequest("GET", ".watcher-history*/_search"));
        Map<String, Object> hits = (Map<String, Object>) history.get("hits");
        assertThat((int) (hits.get("total")), greaterThanOrEqualTo(2));
    }

    private void assertBasicWatchInteractions() throws Exception {

        String watch = new WatchSourceBuilder()
                .condition(AlwaysCondition.INSTANCE)
                .trigger(ScheduleTrigger.builder(new IntervalSchedule(IntervalSchedule.Interval.seconds(1))))
                .addAction("awesome", LoggingAction.builder(new TextTemplate("test"))).buildAsBytes(XContentType.JSON).utf8ToString();
        Map<String, Object> put = toMap(client().performRequest("PUT", "_xpack/watcher/watch/new_watch", emptyMap(),
                new StringEntity(watch, ContentType.APPLICATION_JSON)));

        logger.info(put);

        assertThat(put.get("created"), equalTo(true));
        assertThat(put.get("_version"), equalTo(1));

        put = toMap(client().performRequest("PUT", "_xpack/watcher/watch/new_watch", emptyMap(),
                new StringEntity(watch, ContentType.APPLICATION_JSON)));
        assertThat(put.get("created"), equalTo(false));
        assertThat(put.get("_version"), equalTo(2));

        Map<String, Object> get = toMap(client().performRequest("GET", "_xpack/watcher/watch/new_watch"));
        assertThat(get.get("found"), equalTo(true));
        @SuppressWarnings("unchecked") Map<?, ?> source = (Map<String, Object>) get.get("watch");
        Map<String, Object>  logging = ObjectPath.eval("actions.awesome.logging", source);
        assertEquals("info", logging.get("level"));
        assertEquals("test", logging.get("text"));
    }

    private void waitForYellow(String indexName) throws IOException {
        Map<String, String> params = new HashMap<>();
        params.put("wait_for_status", "yellow");
        params.put("timeout", "30s");
        Map<String, Object> response = toMap(client().performRequest("GET", "/_cluster/health/" + indexName, params));
        assertThat(response.get("timed_out"), equalTo(Boolean.FALSE));
    }

    @SuppressWarnings("unchecked")
    private void waitForHits(String indexName, int expectedHits) throws Exception {
        assertBusy(() -> {
            Map<String, Object> response = toMap(client().performRequest("GET", "/" + indexName + "/_search", singletonMap("size", "0")));
            Map<String, Object> hits = (Map<String, Object>) response.get("hits");
            int total = (int) hits.get("total");
            assertThat(total, greaterThanOrEqualTo(expectedHits));
        }, 30, TimeUnit.SECONDS);
    }

    static Map<String, Object> toMap(Response response) throws IOException {
        return toMap(EntityUtils.toString(response.getEntity()));
    }

    static Map<String, Object> toMap(String response) throws IOException {
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, response, false);
    }

    static String toStr(Response response) throws IOException {
        return EntityUtils.toString(response.getEntity());
    }
}
