/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.ldap;

import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.MockSecureSettings;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.env.TestEnvironment;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.OpenLdapTests;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.security.authc.RealmConfig;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapSession;
import org.elasticsearch.xpack.security.authc.ldap.support.LdapTestCase;
import org.elasticsearch.xpack.security.authc.ldap.support.SessionFactory;
import org.elasticsearch.xpack.ssl.SSLService;
import org.junit.After;
import org.junit.Before;

import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static org.elasticsearch.test.OpenLdapTests.LDAPTRUST_PATH;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

public class OpenLdapUserSearchSessionFactoryTests extends ESTestCase {

    private Settings globalSettings;
    private ThreadPool threadPool;

    @Before
    public void init() throws Exception {
        Path keystore = getDataPath(LDAPTRUST_PATH);
        /*
         * Prior to each test we reinitialize the socket factory with a new SSLService so that we get a new SSLContext.
         * If we re-use a SSLContext, previously connected sessions can get re-established which breaks hostname
         * verification tests since a re-established connection does not perform hostname verification.
         */
        globalSettings = Settings.builder()
                .put("path.home", createTempDir())
                .put("xpack.ssl.truststore.path", keystore)
                .setSecureSettings(newSecureSettings("xpack.ssl.truststore.secure_password", "changeit"))
                .build();
        threadPool = new TestThreadPool("LdapUserSearchSessionFactoryTests");
    }

    @After
    public void shutdown() throws InterruptedException {
        terminate(threadPool);
    }

    public void testUserSearchwithBindUserOpenLDAP() throws Exception {
        String groupSearchBase = "ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com";
        String userSearchBase = "ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com";
        RealmConfig config = new RealmConfig("oldap-test", Settings.builder()
                .put(LdapTestCase.buildLdapSettings(new String[] { OpenLdapTests.OPEN_LDAP_URL }, Strings.EMPTY_ARRAY, groupSearchBase,
                        LdapSearchScope.ONE_LEVEL))
                .put("user_search.base_dn", userSearchBase)
                .put("group_search.user_attribute", "uid")
                .put("bind_dn", "uid=blackwidow,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com")
                .put("bind_password", OpenLdapTests.PASSWORD)
                .put("user_search.pool.enabled", randomBoolean())
                .build(), globalSettings, TestEnvironment.newEnvironment(globalSettings), new ThreadContext(globalSettings));
        Settings.Builder builder = Settings.builder()
                .put(globalSettings);
        builder.put(Settings.builder().put(config.settings()).normalizePrefix("xpack.security.authc.realms.ldap.").build());
        Settings settings = builder.build();
        SSLService sslService = new SSLService(settings, TestEnvironment.newEnvironment(settings));


        String[] users = new String[] { "cap", "hawkeye", "hulk", "ironman", "thor" };
        try (LdapUserSearchSessionFactory sessionFactory = new LdapUserSearchSessionFactory(config, sslService, threadPool)) {
            for (String user : users) {
                //auth
                try (LdapSession ldap = session(sessionFactory, user, new SecureString(OpenLdapTests.PASSWORD))) {
                    assertThat(ldap.userDn(), is(equalTo(new MessageFormat("uid={0},ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com",
                            Locale.ROOT).format(new Object[]{user}, new StringBuffer(), null).toString())));
                    assertThat(groups(ldap), hasItem(containsString("Avengers")));
                }

                //lookup
                try (LdapSession ldap = unauthenticatedSession(sessionFactory, user)) {
                    assertThat(ldap.userDn(), is(equalTo(new MessageFormat("uid={0},ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com",
                            Locale.ROOT).format(new Object[]{user}, new StringBuffer(), null).toString())));
                    assertThat(groups(ldap), hasItem(containsString("Avengers")));
                }
            }
        }
    }

    private MockSecureSettings newSecureSettings(String key, String value) {
        MockSecureSettings secureSettings = new MockSecureSettings();
        secureSettings.setString(key, value);
        return secureSettings;
    }

    private LdapSession session(SessionFactory factory, String username, SecureString password) {
        PlainActionFuture<LdapSession> future = new PlainActionFuture<>();
        factory.session(username, password, future);
        return future.actionGet();
    }

    private List<String> groups(LdapSession ldapSession) {
        Objects.requireNonNull(ldapSession);
        PlainActionFuture<List<String>> future = new PlainActionFuture<>();
        ldapSession.groups(future);
        return future.actionGet();
    }

    private LdapSession unauthenticatedSession(SessionFactory factory, String username) {
        PlainActionFuture<LdapSession> future = new PlainActionFuture<>();
        factory.unauthenticatedSession(username, future);
        return future.actionGet();
    }
}
