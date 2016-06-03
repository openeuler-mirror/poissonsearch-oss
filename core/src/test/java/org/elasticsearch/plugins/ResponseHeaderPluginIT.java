/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.plugins;

import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.ElasticsearchResponse;
import org.elasticsearch.client.ElasticsearchResponseException;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.responseheader.TestResponseHeaderPlugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;

import java.util.Collection;
import java.util.Collections;

import static org.elasticsearch.rest.RestStatus.OK;
import static org.elasticsearch.rest.RestStatus.UNAUTHORIZED;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.hasStatus;
import static org.hamcrest.Matchers.equalTo;

/**
 * Test a rest action that sets special response headers
 */
@ClusterScope(scope = Scope.SUITE, supportsDedicatedMasters = false, numDataNodes = 1)
public class ResponseHeaderPluginIT extends ESIntegTestCase {
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put("force.http.enabled", true)
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(TestResponseHeaderPlugin.class);
    }

    public void testThatSettingHeadersWorks() throws Exception {
        ensureGreen();
        try (RestClient client = restClient()) {
            try {
                client.performRequest("GET", "/_protected", Collections.emptyMap(), null);
                fail("request should have failed");
            } catch(ElasticsearchResponseException e) {
                ElasticsearchResponse response = e.getElasticsearchResponse();
                assertThat(response, hasStatus(UNAUTHORIZED));
                assertThat(response.getFirstHeader("Secret"), equalTo("required"));
            }

            try (ElasticsearchResponse authResponse = client.performRequest("GET", "/_protected", Collections.emptyMap(), null,
                    new BasicHeader("Secret", "password"))) {
                assertThat(authResponse, hasStatus(OK));
                assertThat(authResponse.getFirstHeader("Secret"), equalTo("granted"));
            }
        }
    }
}
