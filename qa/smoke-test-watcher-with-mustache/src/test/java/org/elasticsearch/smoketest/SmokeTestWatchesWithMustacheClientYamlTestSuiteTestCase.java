/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.smoketest;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.test.rest.yaml.ClientYamlTestCandidate;
import org.elasticsearch.test.rest.yaml.ESClientYamlSuiteTestCase;
import org.junit.After;
import org.junit.Before;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

public abstract class SmokeTestWatchesWithMustacheClientYamlTestSuiteTestCase extends ESClientYamlSuiteTestCase {

    public SmokeTestWatchesWithMustacheClientYamlTestSuiteTestCase(@Name("yaml") ClientYamlTestCandidate testCandidate) {
        super(testCandidate);
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        return ESClientYamlSuiteTestCase.createParameters();
    }

    @Before
    public void startWatcher() throws Exception {
        getAdminExecutionContext().callApi("xpack.watcher.start", emptyMap(), emptyList(), emptyMap());
    }

    @After
    public void stopWatcher() throws Exception {
        getAdminExecutionContext().callApi("xpack.watcher.stop", emptyMap(), emptyList(), emptyMap());
    }
}
