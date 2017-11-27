/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.test.rest;


import com.carrotsearch.randomizedtesting.annotations.Name;
import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.Version;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.SecuritySettingsSource;
import org.elasticsearch.test.rest.yaml.ClientYamlTestCandidate;
import org.elasticsearch.test.rest.yaml.ESClientYamlSuiteTestCase;
import org.elasticsearch.xpack.ml.MlMetaIndex;
import org.elasticsearch.xpack.ml.job.persistence.AnomalyDetectorsIndex;
import org.elasticsearch.xpack.ml.notifications.Auditor;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;

public abstract class XPackRestTestCase extends ESClientYamlSuiteTestCase {

    private static final String BASIC_AUTH_VALUE =
            basicAuthHeaderValue("x_pack_rest_user", SecuritySettingsSource.TEST_PASSWORD_SECURE_STRING);

    public XPackRestTestCase(@Name("yaml") ClientYamlTestCandidate testCandidate) {
        super(testCandidate);
    }

    @ParametersFactory
    public static Iterable<Object[]> parameters() throws Exception {
        return ESClientYamlSuiteTestCase.createParameters();
    }

    @Override
    protected Settings restClientSettings() {
        return Settings.builder()
                .put(ThreadContext.PREFIX + ".Authorization", BASIC_AUTH_VALUE)
                .build();
    }

    /**
     * Waits for the Machine Learning templates to be created by {@link org.elasticsearch.plugins.MetaDataUpgrader}.
     */
    public static void waitForMlTemplates() throws InterruptedException {
        AtomicReference<Version> masterNodeVersion = new AtomicReference<>();
        awaitBusy(() -> {
            String response;
            try {
                response = EntityUtils
                        .toString(client().performRequest("GET", "/_cat/nodes", singletonMap("h", "master,version")).getEntity());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (String line : response.split("\n")) {
                if (line.startsWith("*")) {
                    masterNodeVersion.set(Version.fromString(line.substring(2).trim()));
                    return true;
                }
            }
            return false;
        });

        final List<String> templateNames = Arrays.asList(Auditor.NOTIFICATIONS_INDEX, MlMetaIndex.INDEX_NAME,
                AnomalyDetectorsIndex.jobStateIndexName(), AnomalyDetectorsIndex.jobResultsIndexPrefix());
        for (String template : templateNames) {
            awaitBusy(() -> {
                Map<?, ?> response;
                try {
                    String string = EntityUtils.toString(client().performRequest("GET", "/_template/" + template).getEntity());
                    response = XContentHelper.convertToMap(JsonXContent.jsonXContent, string, false);
                } catch (ResponseException e) {
                    if (e.getResponse().getStatusLine().getStatusCode() == 404) {
                        return false;
                    }
                    throw new RuntimeException(e);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                Map<?, ?> templateDefinition = (Map<?, ?>) response.get(template);
                return Version.fromId((Integer) templateDefinition.get("version")).equals(masterNodeVersion.get());
            });
        }
    }
}
