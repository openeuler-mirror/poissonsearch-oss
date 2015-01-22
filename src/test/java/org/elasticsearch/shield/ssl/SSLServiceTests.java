/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.ssl;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.test.junit.annotations.Network;
import org.junit.Before;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSessionContext;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.hamcrest.Matchers.*;

public class SSLServiceTests extends ElasticsearchTestCase {

    Path testnodeStore;

    @Before
    public void setup() throws Exception {
        testnodeStore = Paths.get(getClass().getResource("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks").toURI());
    }

    @Test(expected = ElasticsearchSSLException.class)
    public void testThatInvalidProtocolThrowsException() throws Exception {
        new SSLService(settingsBuilder()
                            .put("shield.ssl.protocol", "non-existing")
                            .put("shield.ssl.keystore.path", testnodeStore)
                            .put("shield.ssl.keystore.password", "testnode")
                            .put("shield.ssl.truststore.path", testnodeStore)
                            .put("shield.ssl.truststore.password", "testnode")
                        .build()).createSSLEngine();
    }

    @Test
    public void testThatCustomTruststoreCanBeSpecified() throws Exception {
        Path testClientStore = Paths.get(getClass().getResource("/org/elasticsearch/shield/transport/ssl/certs/simple/testclient.jks").toURI());

        SSLService sslService = new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", testnodeStore)
                .put("shield.ssl.keystore.password", "testnode")
                .build());

        ImmutableSettings.Builder settingsBuilder = settingsBuilder()
                .put("truststore.path", testClientStore)
                .put("truststore.password", "testclient");

        SSLEngine sslEngineWithTruststore = sslService.createSSLEngine(settingsBuilder.build());
        assertThat(sslEngineWithTruststore, is(not(nullValue())));

        SSLEngine sslEngine = sslService.createSSLEngine();
        assertThat(sslEngineWithTruststore, is(not(sameInstance(sslEngine))));
    }

    @Test
    public void testThatSslContextCachingWorks() throws Exception {
        SSLService sslService = new SSLService(settingsBuilder()
            .put("shield.ssl.keystore.path", testnodeStore)
            .put("shield.ssl.keystore.password", "testnode")
            .build());

        SSLContext sslContext = sslService.getSslContext();
        SSLContext cachedSslContext = sslService.getSslContext();

        assertThat(sslContext, is(sameInstance(cachedSslContext)));
    }

    @Test
    public void testThatKeyStoreAndKeyCanHaveDifferentPasswords() throws Exception {
        Path differentPasswordsStore = Paths.get(getClass().getResource("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode-different-passwords.jks").toURI());
        new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", differentPasswordsStore)
                .put("shield.ssl.keystore.password", "testnode")
                .put("shield.ssl.keystore.key_password", "testnode1")
                .build()).createSSLEngine();
    }

    @Test(expected = ElasticsearchSSLException.class)
    public void testIncorrectKeyPasswordThrowsException() throws Exception {
        Path differentPasswordsStore = Paths.get(getClass().getResource("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode-different-passwords.jks").toURI());
        new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", differentPasswordsStore)
                .put("shield.ssl.keystore.password", "testnode")
                .build()).createSSLEngine();
    }

    @Test
    public void testThatSSLv3IsNotEnabled() throws Exception {
        SSLService sslService = new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", testnodeStore)
                .put("shield.ssl.keystore.password", "testnode")
                .build());
        SSLEngine engine = sslService.createSSLEngine();
        assertThat(Arrays.asList(engine.getEnabledProtocols()), not(hasItem("SSLv3")));
    }

    @Test
    public void testThatSSLSessionCacheHasDefaultLimits() throws Exception {
        SSLService sslService = new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", testnodeStore)
                .put("shield.ssl.keystore.password", "testnode")
                .build());
        SSLSessionContext context = sslService.getSslContext().getServerSessionContext();
        assertThat(context.getSessionCacheSize(), equalTo(1000));
        assertThat(context.getSessionTimeout(), equalTo((int) TimeValue.timeValueHours(24).seconds()));
    }

    @Test
    public void testThatSettingSSLSessionCacheLimitsWorks() throws Exception {
        SSLService sslService = new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", testnodeStore)
                .put("shield.ssl.keystore.password", "testnode")
                .put("shield.ssl.session.cache_size", "300")
                .put("shield.ssl.session.cache_timeout", "600s")
                .build());
        SSLSessionContext context = sslService.getSslContext().getServerSessionContext();
        assertThat(context.getSessionCacheSize(), equalTo(300));
        assertThat(context.getSessionTimeout(), equalTo(600));
    }

    @Test
    public void testThatCreateClientSSLEngineWithoutAnySettingsWorks() throws Exception {
        SSLService sslService = new SSLService(ImmutableSettings.EMPTY);
        SSLEngine sslEngine = sslService.createClientSSLEngine();
        assertThat(sslEngine, notNullValue());
    }

    @Test
    public void testThatCreateClientSSLEngineWithOnlyTruststoreWorks() throws Exception {
        SSLService sslService = new SSLService(settingsBuilder()
                .put("shield.ssl.truststore.path", testnodeStore)
                .put("shield.ssl.truststore.password", "testnode")
                .build());
        SSLEngine sslEngine = sslService.createClientSSLEngine();
        assertThat(sslEngine, notNullValue());
    }

    @Test
    public void testThatCreateClientSSLEngineWithOnlyKeystoreWorks() throws Exception {
        SSLService sslService = new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", testnodeStore)
                .put("shield.ssl.keystore.password", "testnode")
                .build());
        SSLEngine sslEngine = sslService.createClientSSLEngine();
        assertThat(sslEngine, notNullValue());
    }

    @Test
    @Network
    public void testThatClientSSLContextWithoutSettingsWorks() throws Exception {
        SSLService sslService = new SSLService(ImmutableSettings.EMPTY);
        SSLContext sslContext = sslService.getClientSSLContext();
        try (CloseableHttpClient client = HttpClients.custom().setSslcontext(sslContext).build()) {
            // Execute a GET on a site known to have a valid certificate signed by a trusted public CA
            // This will result in a SSLHandshakeException if the SSLContext does not trust the CA, but the default
            // truststore trusts all common public CAs so the handshake will succeed
            client.execute(new HttpGet("https://www.elasticsearch.com/"));
        }
    }

    @Test
    @Network
    public void testThatClientSSLContextWithKeystoreDoesNotTrustAllPublicCAs() throws Exception {
        SSLService sslService = new SSLService(settingsBuilder()
                .put("shield.ssl.keystore.path", testnodeStore)
                .put("shield.ssl.keystore.password", "testnode")
                .build());
        SSLContext sslContext = sslService.getSslContext();
        try (CloseableHttpClient client = HttpClients.custom().setSslcontext(sslContext).build()) {
            // Execute a GET on a site known to have a valid certificate signed by a trusted public CA
            // This will result in a SSLHandshakeException because the truststore is the testnodestore, which doesn't
            // trust any public CAs
            client.execute(new HttpGet("https://www.elasticsearch.com/"));
            fail("A SSLHandshakeException should have been thrown here");
        } catch (Exception e) {
            assertThat(e, instanceOf(SSLHandshakeException.class));
        }
    }
}
