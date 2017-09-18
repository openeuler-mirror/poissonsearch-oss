/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ssl;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManager;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.ssl.TrustConfig.CombiningTrustConfig;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isIn;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class SSLConfigurationTests extends ESTestCase {

    public void testThatSSLConfigurationHasCorrectDefaults() {
        SSLConfiguration globalConfig = new SSLConfiguration(Settings.EMPTY);
        assertThat(globalConfig.keyConfig(), sameInstance(KeyConfig.NONE));
        assertThat(globalConfig.trustConfig(), is(not((globalConfig.keyConfig()))));
        assertThat(globalConfig.trustConfig(), instanceOf(DefaultJDKTrustConfig.class));

        SSLConfiguration scopedConfig = new SSLConfiguration(Settings.EMPTY, globalConfig);
        assertThat(scopedConfig.keyConfig(), sameInstance(globalConfig.keyConfig()));
        assertThat(scopedConfig.trustConfig(), sameInstance(globalConfig.trustConfig()));
    }

    public void testThatOnlyKeystoreInSettingsSetsTruststoreSettings() {
        final String path = getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.jks").toString();
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("keystore.secure_password", "testnode");
        Settings settings = Settings.builder()
                .put("keystore.path", path)
                .setSecureSettings(secureSettings)
                .build();
        // Pass settings in as component settings
        SSLConfiguration globalSettings = new SSLConfiguration(settings);
        SSLConfiguration scopedSettings = new SSLConfiguration(settings, globalSettings);
        SSLConfiguration scopedEmptyGlobalSettings =
                new SSLConfiguration(settings, new SSLConfiguration(Settings.EMPTY));
        for (SSLConfiguration sslConfiguration : Arrays.asList(globalSettings, scopedSettings, scopedEmptyGlobalSettings)) {
            assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
            StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();

            assertThat(ksKeyInfo.keyStorePath, is(equalTo(path)));
            assertThat(ksKeyInfo.keyStorePassword, is(equalTo("testnode")));
            assertThat(ksKeyInfo.keyStoreType, is(equalTo("jks")));
            assertThat(ksKeyInfo.keyPassword, is(equalTo(ksKeyInfo.keyStorePassword)));
            assertThat(ksKeyInfo.keyStoreAlgorithm, is(KeyManagerFactory.getDefaultAlgorithm()));
            assertThat(sslConfiguration.trustConfig(), is(instanceOf(CombiningTrustConfig.class)));
            assertCombiningTrustConfigContainsCorrectIssuers(sslConfiguration);
        }
    }

    public void testKeystorePassword() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("keystore.secure_password", "password");
        Settings settings = Settings.builder()
            .put("keystore.path", "path")
            .setSecureSettings(secureSettings)
            .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStorePassword, is(equalTo("password")));
        assertThat(ksKeyInfo.keyPassword, is(equalTo("password")));
    }

    public void testKeystorePasswordBackcompat() {
        Settings settings = Settings.builder()
            .put("keystore.path", "path")
            .put("keystore.password", "password")
            .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStorePassword, is(equalTo("password")));
        assertThat(ksKeyInfo.keyPassword, is(equalTo("password")));
        assertSettingDeprecationsAndWarnings(new Setting<?>[] {
            SSLConfiguration.SETTINGS_PARSER.legacyKeystorePassword});
    }

    public void testKeystoreKeyPassword() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("keystore.secure_password", "password");
        secureSettings.setString("keystore.secure_key_password", "keypass");
        Settings settings = Settings.builder()
            .put("keystore.path", "path")
            .setSecureSettings(secureSettings)
            .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStorePassword, is(equalTo("password")));
        assertThat(ksKeyInfo.keyPassword, is(equalTo("keypass")));
    }

    public void testKeystoreKeyPasswordBackcompat() {
        Settings settings = Settings.builder()
            .put("keystore.path", "path")
            .put("keystore.password", "password")
            .put("keystore.key_password", "keypass")
            .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStorePassword, is(equalTo("password")));
        assertThat(ksKeyInfo.keyPassword, is(equalTo("keypass")));
        assertSettingDeprecationsAndWarnings(new Setting<?>[] {
            SSLConfiguration.SETTINGS_PARSER.legacyKeystorePassword, SSLConfiguration.SETTINGS_PARSER.legacyKeystoreKeyPassword});
    }

    public void testInferKeystoreTypeFromJksFile() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("keystore.secure_password", "password");
        secureSettings.setString("keystore.secure_key_password", "keypass");
        Settings settings = Settings.builder()
                .put("keystore.path", "xpack/tls/path.jks")
                .setSecureSettings(secureSettings)
                .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStoreType, is(equalTo("jks")));
    }

    public void testInferKeystoreTypeFromPkcs12File() {
        final String ext = randomFrom("p12", "pfx", "pkcs12");
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("keystore.secure_password", "password");
        secureSettings.setString("keystore.secure_key_password", "keypass");
        Settings settings = Settings.builder()
                .put("keystore.path", "xpack/tls/path." + ext)
                .setSecureSettings(secureSettings)
                .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStoreType, is(equalTo("PKCS12")));
    }

    public void testInferKeystoreTypeFromUnrecognised() {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("keystore.secure_password", "password");
        secureSettings.setString("keystore.secure_key_password", "keypass");
        Settings settings = Settings.builder()
                .put("keystore.path", "xpack/tls/path.foo")
                .setSecureSettings(secureSettings)
                .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStoreType, is(equalTo("jks")));
    }

    public void testExplicitKeystoreType() {
        final String ext = randomFrom("p12", "jks");
        final String type = randomAlphaOfLengthBetween(2, 8);
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("keystore.secure_password", "password");
        secureSettings.setString("keystore.secure_key_password", "keypass");
        Settings settings = Settings.builder()
                .put("keystore.path", "xpack/tls/path." + ext)
                .put("keystore.type", type)
                .setSecureSettings(secureSettings)
                .build();
        SSLConfiguration sslConfiguration = new SSLConfiguration(settings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStoreType, is(equalTo(type)));
    }

    public void testThatProfileSettingsOverrideServiceSettings() {
        MockSecureSettings profileSecureSettings = new MockSecureSettings();
        profileSecureSettings.setString("keystore.secure_password", "password");
        profileSecureSettings.setString("keystore.secure_key_password", "key");
        profileSecureSettings.setString("truststore.secure_password", "password for trust");
        Settings profileSettings = Settings.builder()
                .put("keystore.path", "path")
                .put("keystore.algorithm", "algo")
                .put("truststore.path", "trust path")
                .put("truststore.algorithm", "trusted")
                .setSecureSettings(profileSecureSettings)
                .build();

        MockSecureSettings serviceSecureSettings = new MockSecureSettings();
        serviceSecureSettings.setString("xpack.ssl.keystore.secure_password", "comp password");
        serviceSecureSettings.setString("xpack.ssl.keystore.secure_key_password", "comp key");
        serviceSecureSettings.setString("xpack.ssl.truststore.secure_password", "comp password for trust");
        Settings serviceSettings = Settings.builder()
                .put("xpack.ssl.keystore.path", "comp path")
                .put("xpack.ssl.keystore.algorithm", "comp algo")
                .put("xpack.ssl.truststore.path", "comp trust path")
                .put("xpack.ssl.truststore.algorithm", "comp trusted")
                .setSecureSettings(serviceSecureSettings)
                .build();

        SSLConfiguration globalSettings = new SSLConfiguration(serviceSettings);
        SSLConfiguration sslConfiguration = new SSLConfiguration(profileSettings, globalSettings);
        assertThat(sslConfiguration.keyConfig(), instanceOf(StoreKeyConfig.class));
        StoreKeyConfig ksKeyInfo = (StoreKeyConfig) sslConfiguration.keyConfig();
        assertThat(ksKeyInfo.keyStorePath, is(equalTo("path")));
        assertThat(ksKeyInfo.keyStorePassword, is(equalTo("password")));
        assertThat(ksKeyInfo.keyPassword, is(equalTo("key")));
        assertThat(ksKeyInfo.keyStoreAlgorithm, is(equalTo("algo")));
        assertThat(sslConfiguration.trustConfig(), instanceOf(StoreTrustConfig.class));
        StoreTrustConfig ksTrustInfo = (StoreTrustConfig) sslConfiguration.trustConfig();
        assertThat(ksTrustInfo.trustStorePath, is(equalTo("trust path")));
        assertThat(ksTrustInfo.trustStorePassword, is(equalTo("password for trust")));
        assertThat(ksTrustInfo.trustStoreAlgorithm, is(equalTo("trusted")));
    }

    public void testThatEmptySettingsAreEqual() {
        SSLConfiguration sslConfiguration = new SSLConfiguration(Settings.EMPTY);
        SSLConfiguration sslConfiguration1 = new SSLConfiguration(Settings.EMPTY);
        assertThat(sslConfiguration.equals(sslConfiguration1), is(equalTo(true)));
        assertThat(sslConfiguration1.equals(sslConfiguration), is(equalTo(true)));
        assertThat(sslConfiguration.equals(sslConfiguration), is(equalTo(true)));
        assertThat(sslConfiguration1.equals(sslConfiguration1), is(equalTo(true)));

        SSLConfiguration profileSSLConfiguration = new SSLConfiguration(Settings.EMPTY, sslConfiguration);
        assertThat(sslConfiguration.equals(profileSSLConfiguration), is(equalTo(true)));
        assertThat(profileSSLConfiguration.equals(sslConfiguration), is(equalTo(true)));
        assertThat(profileSSLConfiguration.equals(profileSSLConfiguration), is(equalTo(true)));
    }

    public void testThatSettingsWithDifferentKeystoresAreNotEqual() {
        SSLConfiguration sslConfiguration = new SSLConfiguration(Settings.builder()
                .put("keystore.path", "path")
                .build());
        SSLConfiguration sslConfiguration1 = new SSLConfiguration(Settings.builder()
                .put("keystore.path", "path1")
                .build());
        assertThat(sslConfiguration.equals(sslConfiguration1), is(equalTo(false)));
        assertThat(sslConfiguration1.equals(sslConfiguration), is(equalTo(false)));
        assertThat(sslConfiguration.equals(sslConfiguration), is(equalTo(true)));
        assertThat(sslConfiguration1.equals(sslConfiguration1), is(equalTo(true)));
    }

    public void testThatSettingsWithDifferentTruststoresAreNotEqual() {
        SSLConfiguration sslConfiguration = new SSLConfiguration(Settings.builder()
                .put("truststore.path", "/trust")
                .build());
        SSLConfiguration sslConfiguration1 = new SSLConfiguration(Settings.builder()
                .put("truststore.path", "/truststore")
                .build());
        assertThat(sslConfiguration.equals(sslConfiguration1), is(equalTo(false)));
        assertThat(sslConfiguration1.equals(sslConfiguration), is(equalTo(false)));
        assertThat(sslConfiguration.equals(sslConfiguration), is(equalTo(true)));
        assertThat(sslConfiguration1.equals(sslConfiguration1), is(equalTo(true)));
    }

    public void testThatEmptySettingsHaveSameHashCode() {
        SSLConfiguration sslConfiguration = new SSLConfiguration(Settings.EMPTY);
        SSLConfiguration sslConfiguration1 = new SSLConfiguration(Settings.EMPTY);
        assertThat(sslConfiguration.hashCode(), is(equalTo(sslConfiguration1.hashCode())));

        SSLConfiguration profileSettings = new SSLConfiguration(Settings.EMPTY, sslConfiguration);
        assertThat(profileSettings.hashCode(), is(equalTo(sslConfiguration.hashCode())));
    }

    public void testThatSettingsWithDifferentKeystoresHaveDifferentHashCode() {
        SSLConfiguration sslConfiguration = new SSLConfiguration(Settings.builder()
                .put("keystore.path", "path")
                .build());
        SSLConfiguration sslConfiguration1 = new SSLConfiguration(Settings.builder()
                .put("keystore.path", "path1")
                .build());
        assertThat(sslConfiguration.hashCode(), is(not(equalTo(sslConfiguration1.hashCode()))));
    }

    public void testThatSettingsWithDifferentTruststoresHaveDifferentHashCode() {
        SSLConfiguration sslConfiguration = new SSLConfiguration(Settings.builder()
                .put("truststore.path", "/trust")
                .build());
        SSLConfiguration sslConfiguration1 = new SSLConfiguration(Settings.builder()
                .put("truststore.path", "/truststore")
                .build());
        assertThat(sslConfiguration.hashCode(), is(not(equalTo(sslConfiguration1.hashCode()))));
    }

    public void testPEMFile() {
        Environment env = randomBoolean() ? null :
                new Environment(Settings.builder().put("path.home", createTempDir()).build());
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("key", getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.pem"))
            .put("certificate", getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.crt"))
            .setSecureSettings(secureSettings)
            .build();

        SSLConfiguration config = new SSLConfiguration(settings);
        assertThat(config.keyConfig(), instanceOf(PEMKeyConfig.class));
        PEMKeyConfig keyConfig = (PEMKeyConfig) config.keyConfig();
        KeyManager keyManager = keyConfig.createKeyManager(env);
        assertNotNull(keyManager);
        assertThat(config.trustConfig(), instanceOf(CombiningTrustConfig.class));
        assertCombiningTrustConfigContainsCorrectIssuers(config);
    }

    public void testPEMFileBackcompat() {
        Environment env = randomBoolean() ? null :
            new Environment(Settings.builder().put("path.home", createTempDir()).build());
        Settings settings = Settings.builder()
            .put("key",
                getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.pem"))
            .put("key_passphrase", "testnode")
            .put("certificate",
                getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.crt"))
            .build();

        SSLConfiguration config = new SSLConfiguration(settings);
        assertThat(config.keyConfig(), instanceOf(PEMKeyConfig.class));
        PEMKeyConfig keyConfig = (PEMKeyConfig) config.keyConfig();
        KeyManager keyManager = keyConfig.createKeyManager(env);
        assertNotNull(keyManager);
        assertThat(config.trustConfig(), instanceOf(CombiningTrustConfig.class));
        assertCombiningTrustConfigContainsCorrectIssuers(config);
        assertSettingDeprecationsAndWarnings(new Setting<?>[] {SSLConfiguration.SETTINGS_PARSER.legacyKeyPassword});
    }

    public void testPEMKeyAndTrustFiles() {
        Environment env = randomBoolean() ? null :
                new Environment(Settings.builder().put("path.home", createTempDir()).build());
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString("secure_key_passphrase", "testnode");
        Settings settings = Settings.builder()
            .put("key", getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.pem"))
            .put("certificate", getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.crt"))
            .putArray("certificate_authorities",
                      getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.crt").toString(),
                      getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testclient.crt").toString())
            .setSecureSettings(secureSettings)
            .build();

        SSLConfiguration config = new SSLConfiguration(settings);
        assertThat(config.keyConfig(), instanceOf(PEMKeyConfig.class));
        PEMKeyConfig keyConfig = (PEMKeyConfig) config.keyConfig();
        KeyManager keyManager = keyConfig.createKeyManager(env);
        assertNotNull(keyManager);
        assertThat(config.trustConfig(), not(sameInstance(keyConfig)));
        assertThat(config.trustConfig(), instanceOf(PEMTrustConfig.class));
        TrustManager trustManager = keyConfig.createTrustManager(env);
        assertNotNull(trustManager);
    }

    public void testPEMKeyAndTrustFilesBackcompat() {
        Environment env = randomBoolean() ? null :
            new Environment(Settings.builder().put("path.home", createTempDir()).build());
        Settings settings = Settings.builder()
            .put("key", getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.pem"))
            .put("key_passphrase", "testnode")
            .put("certificate", getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.crt"))
            .putArray("certificate_authorities",
                getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testnode.crt").toString(),
                getDataPath("/org/elasticsearch/xpack/security/transport/ssl/certs/simple/testclient.crt").toString())
            .build();

        SSLConfiguration config = new SSLConfiguration(settings);
        assertThat(config.keyConfig(), instanceOf(PEMKeyConfig.class));
        PEMKeyConfig keyConfig = (PEMKeyConfig) config.keyConfig();
        KeyManager keyManager = keyConfig.createKeyManager(env);
        assertNotNull(keyManager);
        assertThat(config.trustConfig(), not(sameInstance(keyConfig)));
        assertThat(config.trustConfig(), instanceOf(PEMTrustConfig.class));
        TrustManager trustManager = keyConfig.createTrustManager(env);
        assertNotNull(trustManager);
        assertSettingDeprecationsAndWarnings(new Setting<?>[] {SSLConfiguration.SETTINGS_PARSER.legacyKeyPassword});
    }

    private void assertCombiningTrustConfigContainsCorrectIssuers(SSLConfiguration sslConfiguration) {
        X509Certificate[] trustConfAcceptedIssuers = sslConfiguration.trustConfig().createTrustManager(null).getAcceptedIssuers();
        X509Certificate[] keyConfAcceptedIssuers = sslConfiguration.keyConfig().createTrustManager(null).getAcceptedIssuers();
        X509Certificate[] defaultAcceptedIssuers = DefaultJDKTrustConfig.INSTANCE.createTrustManager(null).getAcceptedIssuers();
        assertEquals(keyConfAcceptedIssuers.length + defaultAcceptedIssuers.length, trustConfAcceptedIssuers.length);
        assertThat(Arrays.asList(keyConfAcceptedIssuers), everyItem(isIn(trustConfAcceptedIssuers)));
        assertThat(Arrays.asList(defaultAcceptedIssuers), everyItem(isIn(trustConfAcceptedIssuers)));
    }
}
