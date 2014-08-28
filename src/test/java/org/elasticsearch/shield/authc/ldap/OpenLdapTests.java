/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.ldap;

import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.test.junit.annotations.Network;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.net.URISyntaxException;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

public class OpenLdapTests extends ElasticsearchTestCase {
    public static final String OPEN_LDAP_URL = "ldaps://54.200.235.244:636";
    public static final String PASSWORD = "NickFuryHeartsES";
    public static final String SETTINGS_PREFIX = LdapRealm.class.getPackage().getName().substring("com.elasticsearch.".length()) + '.';

    @BeforeClass
    public static void setTrustStore() throws URISyntaxException {
        //LdapModule will set this up as a singleton normally
        new LdapSslSocketFactory(ImmutableSettings.builder()
                .put(SETTINGS_PREFIX + "truststore", new File(LdapConnectionTests.class.getResource("ldaptrust.jks").toURI()))
                .build());
    }

    @Test @Network
    public void test_standardLdapConnection_uid(){
        //openldap does not use cn as naming attributes by default

        String groupSearchBase = "ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com";
        String userTemplate = "uid={0},ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com";
        boolean isSubTreeSearch = true;
        StandardLdapConnectionFactory connectionFactory = new StandardLdapConnectionFactory(
                LdapConnectionTests.buildLdapSettings(OPEN_LDAP_URL, userTemplate, groupSearchBase, isSubTreeSearch));

        String[] users = new String[]{"blackwidow", "cap", "hawkeye", "hulk", "ironman", "thor"};
        for(String user: users) {
            LdapConnection ldap = connectionFactory.bind(user, PASSWORD.toCharArray());
            assertThat(ldap.getGroups(), hasItem(containsString("Avengers")));
            ldap.close();
        }
    }

}
