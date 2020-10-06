/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.test.rest.yaml.ClientYamlTestCandidate;
import org.elasticsearch.test.rest.yaml.ClientYamlTestResponse;
import org.elasticsearch.test.rest.yaml.ESClientYamlSuiteTestCase;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

/**
 * Parent test class for Watcher YAML based REST tests
 */
public abstract class WatcherYamlSuiteTestCase extends ESClientYamlSuiteTestCase {
    public WatcherYamlSuiteTestCase(@Name("yaml") ClientYamlTestCandidate testCandidate) {
        super(testCandidate);
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        return ESClientYamlSuiteTestCase.createParameters();
    }

    @Before
    public final void startWatcher() throws Exception {
        ESTestCase.assertBusy(() -> {
            ClientYamlTestResponse response = getAdminExecutionContext().callApi("watcher.stats", emptyMap(), emptyList(), emptyMap());
            String state = (String) response.evaluate("stats.0.watcher_state");

            switch (state) {
                case "stopped":
                    ClientYamlTestResponse startResponse = getAdminExecutionContext().callApi(
                        "watcher.start",
                        emptyMap(),
                        emptyList(),
                        emptyMap()
                    );
                    boolean isAcknowledged = (boolean) startResponse.evaluate("acknowledged");
                    Assert.assertThat(isAcknowledged, Matchers.is(true));
                    throw new AssertionError("waiting until stopped state reached started state");
                case "stopping":
                    throw new AssertionError("waiting until stopping state reached stopped state to start again");
                case "starting":
                    throw new AssertionError("waiting until starting state reached started state");
                case "started":
                    int watcherCount = (int) response.evaluate("stats.0.watch_count");
                    if (watcherCount > 0) {
                        logger.info("expected 0 active watches, but got [{}], deleting watcher indices again", watcherCount);
                        deleteWatcherIndices();
                    }
                    // all good here, we are done
                    break;
                default:
                    throw new AssertionError("unknown state[" + state + "]");
            }
        });
    }

    @After
    public final void stopWatcher() throws Exception {
        ESTestCase.assertBusy(() -> {
            ClientYamlTestResponse response = getAdminExecutionContext().callApi("watcher.stats", emptyMap(), emptyList(), emptyMap());
            String state = (String) response.evaluate("stats.0.watcher_state");
            switch (state) {
                case "stopped":
                    // all good here, we are done
                    break;
                case "stopping":
                    throw new AssertionError("waiting until stopping state reached stopped state");
                case "starting":
                    throw new AssertionError("waiting until starting state reached started state to stop");
                case "started":
                    ClientYamlTestResponse stopResponse = getAdminExecutionContext().callApi(
                        "watcher.stop",
                        emptyMap(),
                        emptyList(),
                        emptyMap()
                    );
                    boolean isAcknowledged = (boolean) stopResponse.evaluate("acknowledged");
                    Assert.assertThat(isAcknowledged, Matchers.is(true));
                    throw new AssertionError("waiting until started state reached stopped state");
                default:
                    throw new AssertionError("unknown state[" + state + "]");
            }
        }, 60, TimeUnit.SECONDS);
        deleteWatcherIndices();
    }

    private static void deleteWatcherIndices() throws IOException {
        Request deleteWatchesIndexRequest = new Request("DELETE", ".watches");
        deleteWatchesIndexRequest.addParameter("ignore_unavailable", "true");
        final RequestOptions.Builder requestOptions = RequestOptions.DEFAULT.toBuilder();
        requestOptions.setWarningsHandler(warnings -> {
            final String expectedWaring = "this request accesses system indices: [.watches], but in a future major version, direct "
                + "access to system indices will be prevented by default";
            // There might not be a warning if the .watches index doesn't exist
            return (warnings.isEmpty() || warnings.get(0).equals(expectedWaring)) == false;
        });
        deleteWatchesIndexRequest.setOptions(requestOptions);
        ESRestTestCase.adminClient().performRequest(deleteWatchesIndexRequest);

        Request deleteWatchHistoryRequest = new Request("DELETE", ".watcher-history-*");
        deleteWatchHistoryRequest.addParameter("ignore_unavailable", "true");
        ESRestTestCase.adminClient().performRequest(deleteWatchHistoryRequest);
    }
}
