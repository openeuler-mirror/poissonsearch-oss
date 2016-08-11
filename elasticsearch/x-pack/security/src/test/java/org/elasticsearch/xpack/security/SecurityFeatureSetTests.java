/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.XPackLicenseState;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.XPackFeatureSet;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.elasticsearch.xpack.security.authc.Realms;
import org.elasticsearch.xpack.security.authz.store.CompositeRolesStore;
import org.elasticsearch.xpack.security.crypto.CryptoService;
import org.elasticsearch.xpack.security.transport.filter.IPFilter;
import org.elasticsearch.xpack.security.transport.netty3.SecurityNetty3HttpServerTransport;
import org.elasticsearch.xpack.security.transport.netty3.SecurityNetty3Transport;
import org.elasticsearch.xpack.security.user.AnonymousUser;
import org.elasticsearch.xpack.watcher.support.xcontent.XContentSource;
import org.junit.After;
import org.junit.Before;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SecurityFeatureSetTests extends ESTestCase {

    private Settings settings;
    private XPackLicenseState licenseState;
    private Realms realms;
    private IPFilter ipFilter;
    private CompositeRolesStore rolesStore;
    private AuditTrailService auditTrail;
    private CryptoService cryptoService;

    @Before
    public void init() throws Exception {
        settings = Settings.builder().put("path.home", createTempDir()).build();
        licenseState = mock(XPackLicenseState.class);
        realms = mock(Realms.class);
        ipFilter = mock(IPFilter.class);
        rolesStore = mock(CompositeRolesStore.class);
        auditTrail = mock(AuditTrailService.class);
        cryptoService = mock(CryptoService.class);
    }

    @After
    public void resetAnonymous() {
        AnonymousUser.initialize(Settings.EMPTY);
    }

    public void testAvailable() throws Exception {
        SecurityFeatureSet featureSet = new SecurityFeatureSet(settings, licenseState, realms, rolesStore,
                ipFilter, auditTrail, cryptoService);
        boolean available = randomBoolean();
        when(licenseState.isAuthAllowed()).thenReturn(available);
        assertThat(featureSet.available(), is(available));
    }

    public void testEnabledSetting() throws Exception {
        boolean enabled = randomBoolean();
        Settings settings = Settings.builder()
                .put(this.settings)
                .put("xpack.security.enabled", enabled)
                .build();
        SecurityFeatureSet featureSet = new SecurityFeatureSet(settings, licenseState, realms, rolesStore,
                ipFilter, auditTrail, cryptoService);
        assertThat(featureSet.enabled(), is(enabled));
    }

    public void testEnabledDefault() throws Exception {
        SecurityFeatureSet featureSet = new SecurityFeatureSet(settings, licenseState, realms, rolesStore,
                        ipFilter, auditTrail, cryptoService);
        assertThat(featureSet.enabled(), is(true));
    }

    public void testSystemKeyUsageEnabledByCryptoService() {
        final boolean enabled = randomBoolean();

        when(cryptoService.isEncryptionEnabled()).thenReturn(enabled);

        assertThat(SecurityFeatureSet.systemKeyUsage(cryptoService), hasEntry("enabled", enabled));
    }

    public void testSystemKeyUsageNotEnabledIfNull() {
        assertThat(SecurityFeatureSet.systemKeyUsage(null), hasEntry("enabled", false));
    }

    public void testUsage() throws Exception {

        boolean authcAuthzAvailable = randomBoolean();
        when(licenseState.isAuthAllowed()).thenReturn(authcAuthzAvailable);

        Settings.Builder settings = Settings.builder().put(this.settings);

        boolean enabled = randomBoolean();
        settings.put("xpack.security.enabled", enabled);

        final boolean httpSSLEnabled = randomBoolean();
        settings.put(SecurityNetty3HttpServerTransport.SSL_SETTING.getKey(), httpSSLEnabled);
        final boolean transportSSLEnabled = randomBoolean();
        settings.put(SecurityNetty3Transport.SSL_SETTING.getKey(), transportSSLEnabled);
        final boolean auditingEnabled = randomBoolean();
        final String[] auditOutputs = randomFrom(new String[] {"logfile"}, new String[] {"index"}, new String[] {"logfile", "index"});
        when(auditTrail.usageStats())
                .thenReturn(MapBuilder.<String, Object>newMapBuilder()
                        .put("enabled", auditingEnabled)
                        .put("outputs", auditOutputs)
                        .map());

        final boolean httpIpFilterEnabled = randomBoolean();
        final boolean transportIPFilterEnabled = randomBoolean();
        when(ipFilter.usageStats())
                .thenReturn(MapBuilder.<String, Object>newMapBuilder()
                        .put("http", Collections.singletonMap("enabled", httpIpFilterEnabled))
                        .put("transport", Collections.singletonMap("enabled", transportIPFilterEnabled))
                        .map());


        final boolean rolesStoreEnabled = randomBoolean();
        if (rolesStoreEnabled) {
            when(rolesStore.usageStats()).thenReturn(Collections.singletonMap("count", 1));
        } else {
            when(rolesStore.usageStats()).thenReturn(Collections.emptyMap());
        }
        final boolean useSystemKey = randomBoolean();
        when(cryptoService.isEncryptionEnabled()).thenReturn(useSystemKey);

        Map<String, Object> realmsUsageStats = new HashMap<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> realmUsage = new HashMap<>();
            realmsUsageStats.put("type" + i, realmUsage);
            realmUsage.put("key1", Arrays.asList("value" + i));
            realmUsage.put("key2", Arrays.asList(i));
            realmUsage.put("key3", Arrays.asList(i % 2 == 0));
        }
        when(realms.usageStats()).thenReturn(realmsUsageStats);

        final boolean anonymousEnabled = randomBoolean();
        if (anonymousEnabled) {
            AnonymousUser.initialize(Settings.builder().put(AnonymousUser.ROLES_SETTING.getKey(), "foo").build());
        }

        SecurityFeatureSet featureSet = new SecurityFeatureSet(settings.build(), licenseState, realms, rolesStore,
                ipFilter, auditTrail, cryptoService);
        XPackFeatureSet.Usage usage = featureSet.usage();
        assertThat(usage, is(notNullValue()));
        assertThat(usage.name(), is(XPackPlugin.SECURITY));
        assertThat(usage.enabled(), is(enabled));
        assertThat(usage.available(), is(authcAuthzAvailable));
        XContentSource source = new XContentSource(usage);

        if (enabled) {
            if (authcAuthzAvailable) {
                for (int i = 0; i < 5; i++) {
                    assertThat(source.getValue("realms.type" + i + ".key1"), contains("value" + i));
                    assertThat(source.getValue("realms.type" + i + ".key2"), contains(i));
                    assertThat(source.getValue("realms.type" + i + ".key3"), contains(i % 2 == 0));
                }
            } else {
                assertThat(source.getValue("realms"), is(notNullValue()));
            }

            // check SSL
            assertThat(source.getValue("ssl.http.enabled"), is(httpSSLEnabled));
            assertThat(source.getValue("ssl.transport.enabled"), is(transportSSLEnabled));

            // auditing
            assertThat(source.getValue("audit.enabled"), is(auditingEnabled));
            assertThat(source.getValue("audit.outputs"), contains(auditOutputs));

            // ip filter
            assertThat(source.getValue("ipfilter.http.enabled"), is(httpIpFilterEnabled));
            assertThat(source.getValue("ipfilter.transport.enabled"), is(transportIPFilterEnabled));

            // roles
            if (rolesStoreEnabled) {
                assertThat(source.getValue("roles.count"), is(1));
            } else {
                assertThat(((Map) source.getValue("roles")).isEmpty(), is(true));
            }

            // system key
            assertThat(source.getValue("system_key.enabled"), is(useSystemKey));

            // anonymous
            assertThat(source.getValue("anonymous.enabled"), is(anonymousEnabled));
        } else {
            assertThat(source.getValue("realms"), is(nullValue()));
            assertThat(source.getValue("ssl"), is(nullValue()));
            assertThat(source.getValue("audit"), is(nullValue()));
            assertThat(source.getValue("anonymous"), is(nullValue()));
            assertThat(source.getValue("ipfilter"), is(nullValue()));
            assertThat(source.getValue("roles"), is(nullValue()));
        }
    }
}
