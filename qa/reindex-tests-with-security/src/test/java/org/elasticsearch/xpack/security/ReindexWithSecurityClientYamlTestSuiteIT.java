/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security;

import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.test.rest.yaml.ClientYamlTestCandidate;
import org.elasticsearch.test.rest.yaml.ESClientYamlSuiteTestCase;

import java.io.IOException;

import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;

public class ReindexWithSecurityClientYamlTestSuiteIT extends ESClientYamlSuiteTestCase {
    private static final String USER = "test_admin";
    private static final String PASS = "changeme";

    public ReindexWithSecurityClientYamlTestSuiteIT(@Name("yaml") ClientYamlTestCandidate testCandidate) {
        super(testCandidate);
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws IOException {
        return ESClientYamlSuiteTestCase.createParameters();
    }

    /**
     * All tests run as a an administrative user but use <code>es-security-runas-user</code> to become a less privileged user.
     */
    @Override
    protected Settings restClientSettings() {
        String token = basicAuthHeaderValue(USER, new SecureString(PASS.toCharArray()));
        return Settings.builder()
                .put(ThreadContext.PREFIX + ".Authorization", token)
                .build();
    }
}

