/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.integration;

import org.elasticsearch.test.junit.annotations.Network;
import org.junit.Test;

import java.io.IOException;

import static org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import static org.elasticsearch.test.ElasticsearchIntegrationTest.Scope.SUITE;

/**
 * This tests the mapping of multiple groups to a role
 */
@Network
@ClusterScope(scope = SUITE)
public class MultiGroupMappingTests extends AbstractAdLdapRealmTests {

    @Override
    protected String configRoles() {
        return super.configRoles() +
                "\n" +
                "MarvelCharacters:\n" +
                "  cluster: NONE\n" +
                "  indices:\n" +
                "    'marvel_comics': ALL\n";
    }

    @Override
    protected String configRoleMappings() {
        return "MarvelCharacters:  \n" +
                "  - \"CN=SHIELD,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\"\n" +
                "  - \"CN=Avengers,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\"\n" +
                "  - \"CN=Gods,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\"\n" +
                "  - \"CN=Philanthropists,CN=Users,DC=ad,DC=test,DC=elasticsearch,DC=com\"\n" +
                "  - \"cn=SHIELD,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\"\n" +
                "  - \"cn=Avengers,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\"\n" +
                "  - \"cn=Gods,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\"\n" +
                "  - \"cn=Philanthropists,ou=people,dc=oldap,dc=test,dc=elasticsearch,dc=com\"";
    }

    @Test
    public void testGroupMapping() throws IOException {
        String asgardian = "odin";
        String shieldPhilanthropist = realmConfig.loginWithCommonName ? "Bruce Banner" : "hulk";
        String shield = realmConfig.loginWithCommonName ? "Phil Coulson" : "phil";
        String shieldAsgardianPhilanthropist = "thor";
        String noGroupUser = "jarvis";

        assertAccessAllowed(asgardian, "marvel_comics");
        assertAccessAllowed(shieldAsgardianPhilanthropist, "marvel_comics");
        assertAccessAllowed(shieldPhilanthropist, "marvel_comics");
        assertAccessAllowed(shield, "marvel_comics");
        assertAccessDenied(noGroupUser, "marvel_comics");
    }
}
