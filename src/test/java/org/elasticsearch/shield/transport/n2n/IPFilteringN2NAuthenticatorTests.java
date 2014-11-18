/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.n2n;

import com.google.common.io.Files;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.base.Charsets;
import org.elasticsearch.common.net.InetAddresses;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.test.junit.annotations.Network;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.security.Principal;
import java.util.Locale;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.hamcrest.Matchers.is;

/**
 *
 */
public class IPFilteringN2NAuthenticatorTests extends ElasticsearchTestCase {

    public static final Principal NULL_PRINCIPAL = new Principal() {
        @Override
        public String getName() {
            return "null";
        }
    };

    private final Settings resourceWatcherServiceSettings = settingsBuilder()
            .put("watcher.interval.high", TimeValue.timeValueMillis(200))
            .build();

    private ResourceWatcherService resourceWatcherService;
    private File configFile;
    private IPFilteringN2NAuthenticator ipFilteringN2NAuthenticator;
    private ThreadPool threadPool;

    @Before
    public void init() throws Exception {
        configFile = newTempFile();
    }

    @After
    public void shutdown() {
        resourceWatcherService.stop();
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }

    @Test
    public void testThatIpV4AddressesCanBeProcessed() throws Exception {
        writeConfigFile("allow: 127.0.0.1\ndeny: 10.0.0.0/8");

        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsDenied("10.2.3.4");
    }

    @Test
    public void testThatIpV6AddressesCanBeProcessed() throws Exception {
        // you have to use the shortest possible notation in order to match, so
        // 1234:0db8:85a3:0000:0000:8a2e:0370:7334 becomes 1234:db8:85a3:0:0:8a2e:370:7334
        writeConfigFile("allow: 2001:0db8:1234::/48\ndeny: 1234:db8:85a3:0:0:8a2e:370:7334\ndeny: 4321:db8:1234::/48");

        assertAddressIsAllowed("2001:0db8:1234:0000:0000:8a2e:0370:7334");
        assertAddressIsDenied("1234:0db8:85a3:0000:0000:8a2e:0370:7334");
        assertAddressIsDenied("4321:0db8:1234:0000:0000:8a2e:0370:7334");
    }

    @Test
    @Network // requires network for name resolution
    public void testThatHostnamesCanBeProcessed() throws Exception {
        writeConfigFile("allow: localhost\ndeny: '*.google.com'");

        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsDenied("8.8.8.8");
    }

    @Test
    public void testThatFileDeletionResultsInAllowingAll() throws Exception {
        writeConfigFile("deny: 127.0.0.1");

        assertAddressIsDenied("127.0.0.1");

        configFile.delete();
        assertThat(configFile.exists(), is(false));

        sleep(250);
        assertAddressIsAllowed("127.0.0.1");
    }

    @Test
    public void testThatAnAllowAllAuthenticatorWorks() throws Exception {
        writeConfigFile("allow: all");

        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsAllowed("173.194.70.100");
    }

    @Test
    public void testThatCommaSeparatedValuesWork() throws Exception {
        writeConfigFile("allow: 192.168.23.0/24, localhost\ndeny: all");

        assertAddressIsAllowed("192.168.23.1");
        assertAddressIsAllowed("127.0.0.1");
        assertAddressIsDenied("10.1.2.3");
    }

    @Test
    public void testThatOrderIsImportant() throws Exception {
        writeConfigFile("deny: localhost\nallow: localhost");

        assertAddressIsDenied("127.0.0.1");
    }

    @Test
    public void testThatOrderIsImportantViceVersa() throws Exception {
        writeConfigFile("allow: localhost\ndeny: localhost");

        assertAddressIsAllowed("127.0.0.1");
    }

    @Test
    public void testThatEmptyFileDoesNotLeadIntoLoop() throws Exception {
        writeConfigFile("# \n\n");

        assertAddressIsAllowed("127.0.0.1");
    }

    @Test(expected = ElasticsearchParseException.class)
    public void testThatInvalidFileThrowsCorrectException() throws Exception {
        writeConfigFile("deny: all allow: all \n\n");
        IPFilteringN2NAuthenticator.parseFile(configFile.toPath(), logger);
    }

    private void writeConfigFile(String data) throws IOException {
        Files.write(data.getBytes(Charsets.UTF_8), configFile);
        threadPool = new ThreadPool("resourceWatcher");
        resourceWatcherService = new ResourceWatcherService(resourceWatcherServiceSettings, threadPool).start();
        Settings settings = settingsBuilder().put("shield.transport.n2n.ip_filter.file", configFile.getPath()).build();
        ipFilteringN2NAuthenticator = new IPFilteringN2NAuthenticator(settings, new Environment(), resourceWatcherService);
    }

    private void assertAddressIsAllowed(String ... inetAddresses) {
        for (String inetAddress : inetAddresses) {
            String message = String.format(Locale.ROOT, "Expected address %s to be allowed", inetAddress);
            assertThat(message, ipFilteringN2NAuthenticator.authenticate(NULL_PRINCIPAL, InetAddresses.forString(inetAddress), 1024), is(true));
        }
    }

    private void assertAddressIsDenied(String ... inetAddresses) {
        for (String inetAddress : inetAddresses) {
            String message = String.format(Locale.ROOT, "Expected address %s to be denied", inetAddress);
            assertThat(message, ipFilteringN2NAuthenticator.authenticate(NULL_PRINCIPAL, InetAddresses.forString(inetAddress), 1024), is(false));
        }
    }
}
