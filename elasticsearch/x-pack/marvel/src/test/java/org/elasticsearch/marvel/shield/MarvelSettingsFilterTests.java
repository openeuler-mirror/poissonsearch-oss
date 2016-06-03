/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.shield;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.ElasticsearchResponse;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.marvel.MonitoringSettings;
import org.elasticsearch.marvel.test.MarvelIntegTestCase;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.hamcrest.Matchers;

import java.util.Collections;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.extractValue;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.hamcrest.CoreMatchers.nullValue;

public class MarvelSettingsFilterTests extends MarvelIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(NetworkModule.HTTP_ENABLED.getKey(), true)
                .put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.agent.exporters._http.type", "http")
                .put("xpack.monitoring.agent.exporters._http.enabled", false)
                .put("xpack.monitoring.agent.exporters._http.auth.username", "_user")
                .put("xpack.monitoring.agent.exporters._http.auth.password", "_passwd")
                .put("xpack.monitoring.agent.exporters._http.ssl.truststore.path", "/path/to/truststore")
                .put("xpack.monitoring.agent.exporters._http.ssl.truststore.password", "_passwd")
                .put("xpack.monitoring.agent.exporters._http.ssl.hostname_verification", true)
                .build();
    }

    public void testGetSettingsFiltered() throws Exception {
        Header[] headers;
        if (shieldEnabled) {
            headers = new Header[] {
                    new BasicHeader(BASIC_AUTH_HEADER,
                            basicAuthHeaderValue(ShieldSettings.TEST_USERNAME,
                                    new SecuredString(ShieldSettings.TEST_PASSWORD.toCharArray())))};
        } else {
            headers = new Header[0];
        }
        try (ElasticsearchResponse response = getRestClient().performRequest("GET", "/_nodes/settings",
                Collections.emptyMap(), null, headers)) {
            Map<String, Object> responseMap = JsonXContent.jsonXContent.createParser(response.getEntity().getContent()).map();
            @SuppressWarnings("unchecked")
            Map<String, Object> nodes = (Map<String, Object>) responseMap.get("nodes");
            for (Object node : nodes.values()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> settings = (Map<String, Object>) ((Map<String, Object>) node).get("settings");
                assertThat(extractValue("xpack.monitoring.agent.exporters._http.type", settings), Matchers.<Object>equalTo("http"));
                assertThat(extractValue("xpack.monitoring.agent.exporters._http.enabled", settings), Matchers.<Object>equalTo("false"));
                assertNullSetting(settings, "xpack.monitoring.agent.exporters._http.auth.username");
                assertNullSetting(settings, "xpack.monitoring.agent.exporters._http.auth.password");
                assertNullSetting(settings, "xpack.monitoring.agent.exporters._http.ssl.truststore.path");
                assertNullSetting(settings, "xpack.monitoring.agent.exporters._http.ssl.truststore.password");
                assertNullSetting(settings, "xpack.monitoring.agent.exporters._http.ssl.hostname_verification");
            }
        }
    }

    private void assertNullSetting(Map<String, Object> settings, String setting) {
        assertThat(extractValue(setting, settings), nullValue());
    }
}
