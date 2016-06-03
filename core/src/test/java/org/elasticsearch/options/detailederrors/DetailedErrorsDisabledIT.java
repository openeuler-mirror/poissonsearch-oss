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

package org.elasticsearch.options.detailederrors;

import org.elasticsearch.client.ElasticsearchResponse;
import org.elasticsearch.client.ElasticsearchResponseException;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.http.HttpTransportSettings;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.ESIntegTestCase.ClusterScope;
import org.elasticsearch.test.ESIntegTestCase.Scope;

import java.util.Collections;

import static org.hamcrest.Matchers.is;

/**
 * Tests that when disabling detailed errors, a request with the error_trace parameter returns a HTTP 400
 */
@ClusterScope(scope = Scope.TEST, supportsDedicatedMasters = false, numDataNodes = 1)
public class DetailedErrorsDisabledIT extends ESIntegTestCase {
    // Build our cluster settings
    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(NetworkModule.HTTP_ENABLED.getKey(), true)
                .put(HttpTransportSettings.SETTING_HTTP_DETAILED_ERRORS_ENABLED.getKey(), false)
                .build();
    }

    public void testThatErrorTraceParamReturns400() throws Exception {
        try {
            getRestClient().performRequest("DELETE", "/", Collections.singletonMap("error_trace", "true"), null);
            fail("request should have failed");
        } catch(ElasticsearchResponseException e) {
            ElasticsearchResponse response = e.getElasticsearchResponse();
            assertThat(response.getFirstHeader("Content-Type"), is("application/json"));
            assertThat(e.getResponseBody(), is("{\"error\":\"error traces in responses are disabled.\"}"));
            assertThat(response.getStatusLine().getStatusCode(), is(400));
        }
    }
}
