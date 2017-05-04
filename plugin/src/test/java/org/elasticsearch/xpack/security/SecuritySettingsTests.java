/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.XPackSettings;
import org.elasticsearch.xpack.security.audit.index.IndexAuditTrail;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.not;

public class SecuritySettingsTests extends ESTestCase {

    private static final String TRIBE_T1_SECURITY_ENABLED = "tribe.t1." + XPackSettings.SECURITY_ENABLED.getKey();
    private static final String TRIBE_T2_SECURITY_ENABLED = "tribe.t2." + XPackSettings.SECURITY_ENABLED.getKey();

    public void testSecurityIsEnabledByDefaultOnTribes() {
        Settings settings = Settings.builder()
                .put("tribe.t1.cluster.name", "non_existing")
                .put("tribe.t2.cluster.name", "non_existing2")
                .put("tribe.on_conflict", "prefer_t1")
                .build();

        Settings additionalSettings = Security.additionalSettings(settings, false);

        assertThat(additionalSettings.getAsBoolean(TRIBE_T1_SECURITY_ENABLED, null), equalTo(true));
        assertThat(additionalSettings.getAsBoolean(TRIBE_T2_SECURITY_ENABLED, null), equalTo(true));
    }

    public void testSecurityDisabledOnATribe() {
        Settings settings = Settings.builder().put("tribe.t1.cluster.name", "non_existing")
                .put(TRIBE_T1_SECURITY_ENABLED, false)
                .put("tribe.t2.cluster.name", "non_existing").build();

        try {
            Security.additionalSettings(settings, false);
            fail("security cannot change the value of a setting that is already defined, so a exception should be thrown");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), containsString(TRIBE_T1_SECURITY_ENABLED));
        }
    }

    public void testSecurityDisabledOnTribesSecurityAlreadyMandatory() {
        Settings settings = Settings.builder().put("tribe.t1.cluster.name", "non_existing")
                .put(TRIBE_T1_SECURITY_ENABLED, false)
                .put("tribe.t2.cluster.name", "non_existing")
                .putArray("tribe.t1.plugin.mandatory", "test_plugin", XPackPlugin.NAME).build();

        try {
            Security.additionalSettings(settings, false);
            fail("security cannot change the value of a setting that is already defined, so a exception should be thrown");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), containsString(TRIBE_T1_SECURITY_ENABLED));
        }
    }

    public void testSecuritySettingsCopiedForTribeNodes() {
        Settings settings = Settings.builder()
                .put("tribe.t1.cluster.name", "non_existing")
                .put("tribe.t2.cluster.name", "non_existing")
                .put("tribe.on_conflict", "prefer_" + randomFrom("t1", "t2"))
                .put("xpack.security.foo", "bar")
                .put("xpack.security.bar", "foo")
                .putArray("xpack.security.something.else.here", new String[] { "foo", "bar" })
                .build();

        Settings additionalSettings = Security.additionalSettings(settings, false);

        assertThat(additionalSettings.get("xpack.security.foo"), nullValue());
        assertThat(additionalSettings.get("xpack.security.bar"), nullValue());
        assertThat(additionalSettings.getAsArray("xpack.security.something.else.here"), is(Strings.EMPTY_ARRAY));
        assertThat(additionalSettings.get("tribe.t1.xpack.security.foo"), is("bar"));
        assertThat(additionalSettings.get("tribe.t1.xpack.security.bar"), is("foo"));
        assertThat(additionalSettings.getAsArray("tribe.t1.xpack.security.something.else.here"), arrayContaining("foo", "bar"));
        assertThat(additionalSettings.get("tribe.t2.xpack.security.foo"), is("bar"));
        assertThat(additionalSettings.get("tribe.t2.xpack.security.bar"), is("foo"));
        assertThat(additionalSettings.getAsArray("tribe.t2.xpack.security.something.else.here"), arrayContaining("foo", "bar"));
        assertThat(additionalSettings.get("tribe.on_conflict"), nullValue());
        assertThat(additionalSettings.get("tribe.t1.on_conflict"), nullValue());
        assertThat(additionalSettings.get("tribe.t2.on_conflict"), nullValue());
    }

    public void testOnConflictMustBeSetOnTribe() {
        final Settings settings = Settings.builder()
                .put("tribe.t1.cluster.name", "non_existing")
                .put("tribe.t2.cluster.name", "non_existing2")
                .build();

        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> Security.additionalSettings(settings, false));
        assertThat(e.getMessage(), containsString("tribe.on_conflict"));

        final Settings badOnConflict = Settings.builder().put(settings).put("tribe.on_conflict", randomFrom("any", "drop")).build();
        e = expectThrows(IllegalArgumentException.class, () -> Security.additionalSettings(badOnConflict, false));
        assertThat(e.getMessage(), containsString("tribe.on_conflict"));

        Settings goodOnConflict = Settings.builder().put(settings).put("tribe.on_conflict", "prefer_"  + randomFrom("t1", "t2")).build();
        Settings additionalSettings = Security.additionalSettings(goodOnConflict, false);
        assertNotNull(additionalSettings);
    }

    public void testOnConflictWithNoNativeRealms() {
        final Settings noNative = Settings.builder()
                .put("tribe.t1.cluster.name", "non_existing")
                .put("tribe.t2.cluster.name", "non_existing2")
                .put(XPackSettings.RESERVED_REALM_ENABLED_SETTING.getKey(), false)
                .put("xpack.security.authc.realms.foo.type", randomFrom("ldap", "pki", randomAlphaOfLengthBetween(1, 6)))
                .build();
        Settings additionalSettings = Security.additionalSettings(noNative, false);
        assertNotNull(additionalSettings);

        // still with the reserved realm
        final Settings withReserved = Settings.builder()
                .put("tribe.t1.cluster.name", "non_existing")
                .put("tribe.t2.cluster.name", "non_existing2")
                .put("xpack.security.authc.realms.foo.type", randomFrom("ldap", "pki", randomAlphaOfLengthBetween(1, 6)))
                .build();
        IllegalArgumentException e = expectThrows(IllegalArgumentException.class, () -> Security.additionalSettings(withReserved, false));
        assertThat(e.getMessage(), containsString("tribe.on_conflict"));

        // reserved disabled but no realms defined
        final Settings reservedDisabled = Settings.builder()
                .put("tribe.t1.cluster.name", "non_existing")
                .put("tribe.t2.cluster.name", "non_existing2")
                .put(XPackSettings.RESERVED_REALM_ENABLED_SETTING.getKey(), false)
                .build();
        e = expectThrows(IllegalArgumentException.class, () -> Security.additionalSettings(reservedDisabled, false));
        assertThat(e.getMessage(), containsString("tribe.on_conflict"));
    }

    public void testValidAutoCreateIndex() {
        Security.validateAutoCreateIndex(Settings.EMPTY);
        Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", true).build());

        try {
            Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", false).build());
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(SecurityLifecycleService.SECURITY_INDEX_NAME));
            assertThat(e.getMessage(), not(containsString(IndexAuditTrail.INDEX_NAME_PREFIX)));
        }

        Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", ".security").build());
        Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", ".security*").build());
        Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", "*s*").build());
        Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", ".s*").build());

        try {
            Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", "foo").build());
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(SecurityLifecycleService.SECURITY_INDEX_NAME));
            assertThat(e.getMessage(), not(containsString(IndexAuditTrail.INDEX_NAME_PREFIX)));
        }

        try {
            Security.validateAutoCreateIndex(Settings.builder().put("action.auto_create_index", ".security_audit_log*").build());
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(SecurityLifecycleService.SECURITY_INDEX_NAME));
        }

        Security.validateAutoCreateIndex(Settings.builder()
                        .put("action.auto_create_index", ".security")
                        .put(XPackSettings.AUDIT_ENABLED.getKey(), true)
                        .build());

        try {
            Security.validateAutoCreateIndex(Settings.builder()
                    .put("action.auto_create_index", ".security")
                    .put(XPackSettings.AUDIT_ENABLED.getKey(), true)
                    .put(Security.AUDIT_OUTPUTS_SETTING.getKey(), randomFrom("index", "logfile,index"))
                    .build());
            fail("IllegalArgumentException expected");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString(SecurityLifecycleService.SECURITY_INDEX_NAME));
            assertThat(e.getMessage(), containsString(IndexAuditTrail.INDEX_NAME_PREFIX));
        }

        Security.validateAutoCreateIndex(Settings.builder()
                .put("action.auto_create_index", ".security_audit_log*,.security")
                .put(XPackSettings.AUDIT_ENABLED.getKey(), true)
                .put(Security.AUDIT_OUTPUTS_SETTING.getKey(), randomFrom("index", "logfile,index"))
                .build());
    }
}
