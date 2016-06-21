/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.test;

import org.elasticsearch.common.inject.Guice;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.xpack.security.ssl.SSLConfiguration;
import org.elasticsearch.xpack.XPackPlugin;
import org.hamcrest.Matcher;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.is;

public class SettingsFilterTests extends ESTestCase {

    private Settings.Builder configuredSettingsBuilder = Settings.builder();
    private Map<String, Matcher> settingsMatcherMap = new HashMap<>();

    public void testFiltering() {
        configureUnfilteredSetting("xpack.security.authc.realms.file.type", "file");

        // ldap realm filtering
        configureUnfilteredSetting("xpack.security.authc.realms.ldap1.type", "ldap");
        configureUnfilteredSetting("xpack.security.authc.realms.ldap1.enabled", "false");
        configureUnfilteredSetting("xpack.security.authc.realms.ldap1.url", "ldap://host.domain");
        configureFilteredSetting("xpack.security.authc.realms.ldap1.hostname_verification", randomAsciiOfLength(5));
        configureFilteredSetting("xpack.security.authc.realms.ldap1.bind_dn", randomAsciiOfLength(5));
        configureFilteredSetting("xpack.security.authc.realms.ldap1.bind_password", randomAsciiOfLength(5));

        // active directory filtering
        configureUnfilteredSetting("xpack.security.authc.realms.ad1.type", "active_directory");
        configureUnfilteredSetting("xpack.security.authc.realms.ad1.enabled", "false");
        configureUnfilteredSetting("xpack.security.authc.realms.ad1.url", "ldap://host.domain");
        configureFilteredSetting("xpack.security.authc.realms.ad1.hostname_verification", randomAsciiOfLength(5));

        // pki filtering
        configureUnfilteredSetting("xpack.security.authc.realms.pki1.type", "pki");
        configureUnfilteredSetting("xpack.security.authc.realms.pki1.order", "0");
        configureFilteredSetting("xpack.security.authc.realms.pki1.truststore.path",
                getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/truststore-testnode-only.jks").toString());
        configureFilteredSetting("xpack.security.authc.realms.pki1.truststore.password", "truststore-testnode-only");
        configureFilteredSetting("xpack.security.authc.realms.pki1.truststore.algorithm", "SunX509");

        configureFilteredSetting("xpack.security.ssl.keystore.path", "/path/to/keystore");
        configureFilteredSetting("xpack.security.ssl.ciphers", "_ciphers");
        configureFilteredSetting("xpack.security.ssl.supported_protocols", randomFrom(SSLConfiguration.Global.DEFAULT_SUPPORTED_PROTOCOLS));
        configureFilteredSetting("xpack.security.ssl.keystore.password", randomAsciiOfLength(5));
        configureFilteredSetting("xpack.security.ssl.keystore.algorithm", "_algorithm");
        configureFilteredSetting("xpack.security.ssl.keystore.key_password", randomAsciiOfLength(5));
        configureFilteredSetting("xpack.security.ssl.truststore.password", randomAsciiOfLength(5));
        configureFilteredSetting("xpack.security.ssl.truststore.algorithm", "_algorithm");

        // client profile
        configureUnfilteredSetting("transport.profiles.client.port", "9500-9600");
        configureFilteredSetting("transport.profiles.client.xpack.security.keystore.path", "/path/to/keystore");
        configureFilteredSetting("transport.profiles.client.xpack.security.ciphers", "_ciphers");
        configureFilteredSetting("transport.profiles.client.xpack.security.supported_protocols",
                randomFrom(SSLConfiguration.Global.DEFAULT_SUPPORTED_PROTOCOLS));
        configureFilteredSetting("transport.profiles.client.xpack.security.keystore.password", randomAsciiOfLength(5));
        configureFilteredSetting("transport.profiles.client.xpack.security.keystore.algorithm", "_algorithm");
        configureFilteredSetting("transport.profiles.client.xpack.security.keystore.key_password", randomAsciiOfLength(5));
        configureFilteredSetting("transport.profiles.client.xpack.security.truststore.password", randomAsciiOfLength(5));
        configureFilteredSetting("transport.profiles.client.xpack.security.truststore.algorithm", "_algorithm");

        // custom settings, potentially added by a plugin
        configureFilteredSetting("foo.bar", "_secret");
        configureFilteredSetting("foo.baz", "_secret");;
        configureFilteredSetting("bar.baz", "_secret");
        configureUnfilteredSetting("baz.foo", "_not_a_secret");
        configureFilteredSetting("xpack.security.hide_settings", "foo.*,bar.baz");

        Settings settings = Settings.builder()
                .put("path.home", createTempDir())
                .put(configuredSettingsBuilder.build())
                .build();

        XPackPlugin xPackPlugin = new XPackPlugin(settings);
        List<Setting<?>> settingList = new ArrayList<>();
        settingList.add(Setting.simpleString("foo.bar", Setting.Property.NodeScope));
        settingList.add(Setting.simpleString("foo.baz", Setting.Property.NodeScope));
        settingList.add(Setting.simpleString("bar.baz", Setting.Property.NodeScope));
        settingList.add(Setting.simpleString("baz.foo", Setting.Property.NodeScope));
        settingList.addAll(xPackPlugin.getSettings());
        // custom settings, potentially added by a plugin
        SettingsModule settingsModule = new SettingsModule(settings, settingList, xPackPlugin.getSettingsFilter());

        Injector injector = Guice.createInjector(settingsModule);
        SettingsFilter settingsFilter = injector.getInstance(SettingsFilter.class);

        Settings filteredSettings = settingsFilter.filter(settings);
        for (Map.Entry<String, Matcher> entry : settingsMatcherMap.entrySet()) {
            assertThat(filteredSettings.get(entry.getKey()), entry.getValue());
        }
    }

    private void configureUnfilteredSetting(String settingName, String value) {
        configureSetting(settingName, value, is(value));
    }

    private void configureFilteredSetting(String settingName, String value) {
        configureSetting(settingName, value, is(nullValue()));
    }

    private void configureSetting(String settingName, String value, Matcher expectedMatcher) {
        configuredSettingsBuilder.put(settingName, value);
        settingsMatcherMap.put(settingName, expectedMatcher);
    }
}
