/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.monitoring.security;

import org.apache.http.Header;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Response;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.MockNetty3Plugin;
import org.elasticsearch.xpack.monitoring.MonitoringSettings;
import org.elasticsearch.xpack.monitoring.test.MonitoringIntegTestCase;
import org.elasticsearch.xpack.security.authc.support.SecuredString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.extractValue;
import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class MonitoringSettingsFilterTests extends MonitoringIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(NetworkModule.HTTP_ENABLED.getKey(), true)
                .put(MonitoringSettings.INTERVAL.getKey(), "-1")
                .put("xpack.monitoring.exporters._http.type", "http")
                .put("xpack.monitoring.exporters._http.enabled", false)
                .put("xpack.monitoring.exporters._http.auth.username", "_user")
                .put("xpack.monitoring.exporters._http.auth.password", "_passwd")
                .put("xpack.monitoring.exporters._http.ssl.truststore.path", "/path/to/truststore")
                .put("xpack.monitoring.exporters._http.ssl.truststore.password", "_passwd")
                .put("xpack.monitoring.exporters._http.ssl.hostname_verification", true)
                .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        ArrayList<Class<? extends Plugin>> plugins = new ArrayList<>(super.nodePlugins());
        plugins.add(MockNetty3Plugin.class); // for http
        return plugins;
    }

    public void testGetSettingsFiltered() throws Exception {
        Header[] headers;
        if (securityEnabled) {
            headers = new Header[] {
                    new BasicHeader(BASIC_AUTH_HEADER,
                            basicAuthHeaderValue(SecuritySettings.TEST_USERNAME,
                                    new SecuredString(SecuritySettings.TEST_PASSWORD.toCharArray())))};
        } else {
            headers = new Header[0];
        }
        Response response = getRestClient().performRequest("GET", "/_nodes/settings", headers);
        Map<String, Object> responseMap = JsonXContent.jsonXContent.createParser(response.getEntity().getContent()).map();
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) responseMap.get("nodes");
        for (Object node : nodes.values()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> settings = (Map<String, Object>) ((Map<String, Object>) node).get("settings");
            assertThat(extractValue("xpack.monitoring.exporters._http.type", settings), equalTo("http"));
            assertThat(extractValue("xpack.monitoring.exporters._http.enabled", settings), equalTo("false"));
            assertNullSetting(settings, "xpack.monitoring.exporters._http.auth.username");
            assertNullSetting(settings, "xpack.monitoring.exporters._http.auth.password");
            assertNullSetting(settings, "xpack.monitoring.exporters._http.ssl.truststore.path");
            assertNullSetting(settings, "xpack.monitoring.exporters._http.ssl.truststore.password");
            assertNullSetting(settings, "xpack.monitoring.exporters._http.ssl.hostname_verification");
        }
    }

    private void assertNullSetting(Map<String, Object> settings, String setting) {
        assertThat(extractValue(setting, settings), nullValue());
    }
}
