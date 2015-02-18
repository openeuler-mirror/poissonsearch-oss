/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration.ldap;

import com.carrotsearch.randomizedtesting.LifecycleScope;
import org.apache.lucene.util.AbstractRandomizedTest;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.logging.ESLoggerFactory;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.authc.activedirectory.ActiveDirectoryRealm;
import org.elasticsearch.shield.authc.ldap.LdapRealm;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.authc.support.UsernamePasswordToken;
import org.elasticsearch.shield.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.shield.authz.AuthorizationException;
import org.elasticsearch.shield.transport.netty.ShieldNettyTransport;
import org.elasticsearch.test.ShieldIntegrationTest;
import org.junit.BeforeClass;
import org.junit.Ignore;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static org.elasticsearch.common.settings.ImmutableSettings.settingsBuilder;
import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.BASIC_AUTH_HEADER;
import static org.elasticsearch.shield.test.ShieldTestUtils.writeFile;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;


/**
 * This test assumes all subclass tests will be of type SUITE.  It picks a random realm configuration for the tests, and
 * writes a group to role mapping file for each node.
 */
@Ignore
@AbstractRandomizedTest.Integration
abstract public class AbstractAdLdapRealmTests extends ShieldIntegrationTest {

    public static final String SHIELD_AUTHC_REALMS_EXTERNAL = "shield.authc.realms.external";
    public static final String PASSWORD = "NickFuryHeartsES";
    public static final String ASGARDIAN_INDEX = "gods";
    public static final String PHILANTHROPISTS_INDEX = "philanthropists";
    public static final String SHIELD_INDEX = "shield";
    private static final String AD_ROLE_MAPPING =
            "SHIELD:  [ \"CN=SHIELD,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\" ] \n" +
                    "Avengers:  [ \"CN=Avengers,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\" ] \n" +
                    "Gods:  [ \"CN=Gods,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\" ] \n" +
                    "Philanthropists:  [ \"CN=Philanthropists,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\" ] \n";
    private static final String OLDAP_ROLE_MAPPING =
            "SHIELD: [ \"cn=SHIELD,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\" ] \n" +
                    "Avengers: [ \"cn=Avengers,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\" ] \n" +
                    "Gods: [ \"cn=Gods,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\" ] \n" +
                    "Philanthropists: [ \"cn=Philanthropists,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\" ] \n";

    static protected RealmConfig realmConfig;

    @BeforeClass
    public static void setupRealm() {
        realmConfig = randomFrom(RealmConfig.values());
        ESLoggerFactory.getLogger("test").info("running test with realm configuration [{}], with direct group to role mapping [{}]",
                realmConfig, realmConfig.mapGroupsAsRoles);
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        File nodeFiles = newTempDir(LifecycleScope.SUITE);
        return ImmutableSettings.builder()
                .put(super.nodeSettings(nodeOrdinal))
                .put(realmConfig.buildSettings())
                .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".files.role_mapping", writeFile(nodeFiles, "role_mapping.yml", configRoleMappings()))
                .build();
    }

    protected String configRoleMappings() {
        return realmConfig.configRoleMappings();
    }

    @Override
    protected String configRoles() {
        return super.configRoles() +
                "\n" +
                "Avengers:\n" +
                "  cluster: NONE\n" +
                "  indices:\n" +
                "    'avengers': ALL\n" +
                "SHIELD:\n" +
                "  cluster: NONE\n" +
                "  indices:\n " +
                "    '" + SHIELD_INDEX + "': ALL\n" +
                "Gods:\n" +
                "  cluster: NONE\n" +
                "  indices:\n" +
                "    '" + ASGARDIAN_INDEX + "': ALL\n" +
                "Philanthropists:\n" +
                "  cluster: NONE\n" +
                "  indices:\n" +
                "    '" + PHILANTHROPISTS_INDEX + "': ALL\n";
    }

    protected void assertAccessAllowed(String user, String index) throws IOException {
        IndexResponse indexResponse = client().prepareIndex(index, "type").
                setSource(jsonBuilder()
                        .startObject()
                        .field("name", "value")
                        .endObject())
                .putHeader(BASIC_AUTH_HEADER, userHeader(user, PASSWORD))
                .execute().actionGet();

        assertThat("user " + user + " should have write access to index " + index, indexResponse.isCreated(), is(true));

        refresh();

        GetResponse getResponse = client().prepareGet(index, "type", indexResponse.getId())
                .putHeader(BASIC_AUTH_HEADER, userHeader(user, PASSWORD))
                .get();

        assertThat("user " + user + " should have read access to index " + index, getResponse.getId(), equalTo(indexResponse.getId()));
    }

    protected void assertAccessDenied(String user, String index) throws IOException {
        try {
            client().prepareIndex(index, "type").
                    setSource(jsonBuilder()
                            .startObject()
                            .field("name", "value")
                            .endObject())
                    .putHeader(BASIC_AUTH_HEADER, userHeader(user, PASSWORD))
                    .execute().actionGet();
            fail("Write access to index " + index + " should not be allowed for user " + user);
        } catch (AuthorizationException e) {

        }
        refresh();
    }

    protected static String userHeader(String username, String password) {
        return UsernamePasswordToken.basicAuthHeaderValue(username, new SecuredString(password.toCharArray()));
    }

    private static Settings sslSettingsForStore(String resourcePathToStore, String password) {
        File store;
        try {
            store = new File(AbstractAdLdapRealmTests.class.getResource(resourcePathToStore).toURI());
        } catch (URISyntaxException e) {
            throw new ElasticsearchException("exception while reading the store", e);
        }

        if (!store.exists()) {
            throw new ElasticsearchException("store path doesn't exist");
        }

        return settingsBuilder()
                .put("shield.ssl.keystore.path", store.getPath())
                .put("shield.ssl.keystore.password", password)
                .put(ShieldNettyTransport.HOSTNAME_VERIFICATION_SETTING, false)
                .put("shield.ssl.truststore.path", store.getPath())
                .put("shield.ssl.truststore.password", password).build();
    }

    /**
     * Represents multiple possible configurations for active directory and ldap
     */
    enum RealmConfig {

        AD(false, AD_ROLE_MAPPING,
                ImmutableSettings.builder()
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".type", ActiveDirectoryRealm.TYPE)
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".domain_name", "ad.test.elasticsearch.com")
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".group_search.base_dn", "CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com")
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".group_search.scope", randomBoolean() ? LdapSearchScope.SUB_TREE : LdapSearchScope.ONE_LEVEL)
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".url", "ldaps://ad.test.elasticsearch.com:636")
                        .build()),

        AD_LDAP_GROUPS_FROM_SEARCH(true, AD_ROLE_MAPPING,
                ImmutableSettings.builder()
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".type", LdapRealm.TYPE)
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".url", "ldaps://ad.test.elasticsearch.com:636")
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".group_search.base_dn", "CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com")
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".group_search.scope", randomBoolean() ? LdapSearchScope.SUB_TREE : LdapSearchScope.ONE_LEVEL)
                        .putArray(SHIELD_AUTHC_REALMS_EXTERNAL + ".user_dn_templates", "cn={0},CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com")
                        .build()),

        AD_LDAP_GROUPS_FROM_ATTRIBUTE(true, AD_ROLE_MAPPING,
                ImmutableSettings.builder()
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".type", LdapRealm.TYPE)
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".url", "ldaps://ad.test.elasticsearch.com:636")
                        .putArray(SHIELD_AUTHC_REALMS_EXTERNAL + ".user_dn_templates", "cn={0},CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com")
                        .build()),

        OLDAP(false, OLDAP_ROLE_MAPPING,
                ImmutableSettings.builder()
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".type", LdapRealm.TYPE)
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".url", "ldaps://54.200.235.244:636")
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".group_search.base_dn", "ou=people, dc=oldap, dc=test, dc=elasticsearch, dc=com")
                        .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".group_search.scope", randomBoolean() ? LdapSearchScope.SUB_TREE : LdapSearchScope.ONE_LEVEL)
                        .putArray(SHIELD_AUTHC_REALMS_EXTERNAL + ".user_dn_templates", "uid={0},ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com")
                        .build());

        final boolean mapGroupsAsRoles;
        final boolean loginWithCommonName;
        private final String roleMappings;
        private final Settings settings;

        RealmConfig(boolean loginWithCommonName, String roleMappings, Settings settings) {
            this.settings = settings;
            this.loginWithCommonName = loginWithCommonName;
            this.roleMappings = roleMappings;
            this.mapGroupsAsRoles = randomBoolean();
        }

        public Settings buildSettings() {
            ImmutableSettings.Builder builder = ImmutableSettings.builder()
                    .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".order", 1)
                    .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".hostname_verification", false)
                    .put(SHIELD_AUTHC_REALMS_EXTERNAL + ".unmapped_groups_as_roles", mapGroupsAsRoles)
                    .put(sslSettingsForStore("/org/elasticsearch/shield/transport/ssl/certs/simple/testnode.jks", "testnode")) //we need ssl to the LDAP server
                    .put(this.settings);

            return builder.build();
        }

        //if mapGroupsAsRoles is turned on we don't write anything to the rolemapping file
        public String configRoleMappings() {
            return mapGroupsAsRoles ? "" : roleMappings;
        }
    }
}
