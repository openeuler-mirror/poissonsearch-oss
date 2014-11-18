/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.ldap;

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Guice;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.shield.ssl.SSLService;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.mock;

/**
 *
 */
public class LdapModuleTests extends ElasticsearchTestCase {

    private ThreadPool threadPool;

    @Before
    public void init() throws Exception {
        threadPool = new ThreadPool("test");
    }

    @After
    public void shutdown() {
        threadPool.shutdownNow();
    }

    @Test
    public void testEnabled() throws Exception {
        assertThat(LdapModule.enabled(ImmutableSettings.EMPTY), is(false));
        Settings settings = ImmutableSettings.builder()
                .put("shield.authc", false)
                .build();
        assertThat(LdapModule.enabled(settings), is(false));
        settings = ImmutableSettings.builder()
                .put("shield.authc.ldap.enabled", false)
                .build();
        assertThat(LdapModule.enabled(settings), is(false));
        settings = ImmutableSettings.builder()
                .put("shield.authc.ldap.enabled", true)
                .build();
        assertThat(LdapModule.enabled(settings), is(true));
    }

    @Test
    public void test() throws Exception {
        Settings settings = ImmutableSettings.builder()
                .put("client.type", "node")
                .put("shield.authc.ldap.url", "ldap://example.com:389")
                .build();
        Injector injector = Guice.createInjector(new TestModule(settings), new LdapModule(settings));
        LdapRealm realm = injector.getInstance(LdapRealm.class);
        assertThat(realm, notNullValue());
    }

    public class TestModule extends AbstractModule {
        private final Settings settings;

        public TestModule(Settings settings) {
            this.settings = settings;
        }

        @Override
        protected void configure() {
            Environment env = new Environment(settings);
            bind(Settings.class).toInstance(settings);
            bind(Environment.class).toInstance(env);
            bind(ThreadPool.class).toInstance(threadPool);
            bind(ResourceWatcherService.class).asEagerSingleton();
            bind(RestController.class).toInstance(mock(RestController.class));
            bind(SSLService.class).toProvider(Providers.<SSLService>of(null));
        }
    }
}
