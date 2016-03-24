/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esnative;

import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.shield.ShieldTemplateService;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.action.role.DeleteRoleResponse;
import org.elasticsearch.shield.action.role.GetRolesResponse;
import org.elasticsearch.shield.action.user.DeleteUserResponse;
import org.elasticsearch.shield.action.user.GetUsersResponse;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.authz.RoleDescriptor;
import org.elasticsearch.shield.authz.permission.Role;
import org.elasticsearch.shield.client.SecurityClient;
import org.elasticsearch.test.NativeRealmIntegTestCase;
import org.elasticsearch.test.ShieldSettingsSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.shield.authc.support.UsernamePasswordToken.basicAuthHeaderValue;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/**
 * Tests for the ESNativeUsersStore and ESNativeRolesStore
 */
public class ESNativeTests extends NativeRealmIntegTestCase {

    public void testDeletingNonexistingUserAndRole() throws Exception {
        SecurityClient c = securityClient();
        DeleteUserResponse resp = c.prepareDeleteUser("joe").get();
        assertFalse("user shouldn't be found", resp.found());
        DeleteRoleResponse resp2 = c.prepareDeleteRole("role").get();
        assertFalse("role shouldn't be found", resp2.found());
    }

    public void testGettingUserThatDoesntExist() throws Exception {
        SecurityClient c = securityClient();
        GetUsersResponse resp = c.prepareGetUsers("joe").get();
        assertFalse("user should not exist", resp.hasUsers());
        GetRolesResponse resp2 = c.prepareGetRoles().names("role").get();
        assertFalse("role should not exist", resp2.hasRoles());
    }

    public void testAddAndGetUser() throws Exception {
        SecurityClient c = securityClient();
        logger.error("--> creating user");
        c.preparePutUser("joe", "s3kirt".toCharArray(), "role1", "user").get();
        logger.error("--> waiting for .shield index");
        ensureGreen(ShieldTemplateService.SECURITY_INDEX_NAME);
        logger.info("--> retrieving user");
        GetUsersResponse resp = c.prepareGetUsers("joe").get();
        assertTrue("user should exist", resp.hasUsers());
        User joe = resp.users()[0];
        assertEquals(joe.principal(), "joe");
        assertArrayEquals(joe.roles(), new String[]{"role1", "user"});

        logger.info("--> adding two more users");
        c.preparePutUser("joe2", "s3kirt2".toCharArray(), "role2", "user").get();
        c.preparePutUser("joe3", "s3kirt3".toCharArray(), "role3", "user").get();
        GetUsersResponse allUsersResp = c.prepareGetUsers().get();
        assertTrue("users should exist", allUsersResp.hasUsers());
        assertEquals("should be 3 users total", 3, allUsersResp.users().length);
        List<String> names = new ArrayList<>(3);
        for (User u : allUsersResp.users()) {
            names.add(u.principal());
        }
        CollectionUtil.timSort(names);
        assertArrayEquals(new String[] { "joe", "joe2", "joe3" }, names.toArray(Strings.EMPTY_ARRAY));

        GetUsersResponse someUsersResp = c.prepareGetUsers("joe", "joe3").get();
        assertTrue("users should exist", someUsersResp.hasUsers());
        assertEquals("should be 2 users returned", 2, someUsersResp.users().length);
        names = new ArrayList<>(2);
        for (User u : someUsersResp.users()) {
            names.add(u.principal());
        }
        CollectionUtil.timSort(names);
        assertArrayEquals(new String[]{"joe", "joe3"}, names.toArray(Strings.EMPTY_ARRAY));

        logger.info("--> deleting user");
        DeleteUserResponse delResp = c.prepareDeleteUser("joe").get();
        assertTrue(delResp.found());
        logger.info("--> retrieving user");
        resp = c.prepareGetUsers("joe").get();
        assertFalse("user should not exist after being deleted", resp.hasUsers());
    }

    public void testAddAndGetRole() throws Exception {
        SecurityClient c = securityClient();
        logger.error("--> creating role");
        c.preparePutRole("test_role")
                .cluster("all", "none")
                .runAs("root", "nobody")
                .addIndices(new String[]{"index"}, new String[]{"read"},
                        new String[]{"body", "title"}, new BytesArray("{\"query\": {\"match_all\": {}}}"))
                .get();
        logger.error("--> waiting for .shield index");
        ensureGreen(ShieldTemplateService.SECURITY_INDEX_NAME);
        logger.info("--> retrieving role");
        GetRolesResponse resp = c.prepareGetRoles().names("test_role").get();
        assertTrue("role should exist", resp.hasRoles());
        RoleDescriptor testRole = resp.roles()[0];
        assertNotNull(testRole);

        c.preparePutRole("test_role2")
                .cluster("all", "none")
                .runAs("root", "nobody")
                .addIndices(new String[]{"index"}, new String[]{"read"},
                        new String[]{"body", "title"}, new BytesArray("{\"query\": {\"match_all\": {}}}"))
                .get();
        c.preparePutRole("test_role3")
                .cluster("all", "none")
                .runAs("root", "nobody")
                .addIndices(new String[]{"index"}, new String[]{"read"},
                        new String[]{"body", "title"}, new BytesArray("{\"query\": {\"match_all\": {}}}"))
                .get();

        logger.info("--> retrieving all roles");
        GetRolesResponse allRolesResp = c.prepareGetRoles().get();
        assertTrue("roles should exist", allRolesResp.hasRoles());
        assertEquals("should be 3 roles total", 3, allRolesResp.roles().length);

        logger.info("--> retrieving all roles");
        GetRolesResponse someRolesResp = c.prepareGetRoles().names("test_role", "test_role3").get();
        assertTrue("roles should exist", someRolesResp.hasRoles());
        assertEquals("should be 2 roles total", 2, someRolesResp.roles().length);

        logger.info("--> deleting role");
        DeleteRoleResponse delResp = c.prepareDeleteRole("test_role").get();
        assertTrue(delResp.found());
        logger.info("--> retrieving role");
        GetRolesResponse resp2 = c.prepareGetRoles().names("test_role").get();
        assertFalse("role should not exist after being deleted", resp2.hasRoles());
    }

    public void testAddUserAndRoleThenAuth() throws Exception {
        SecurityClient c = securityClient();
        logger.error("--> creating role");
        c.preparePutRole("test_role")
                .cluster("all")
                .addIndices(new String[] { "*" }, new String[] { "read" },
                        new String[] { "body", "title" }, new BytesArray("{\"match_all\": {}}"))
                .get();
        logger.error("--> creating user");
        c.preparePutUser("joe", "s3krit".toCharArray(), "test_role").get();
        logger.error("--> waiting for .shield index");
        ensureGreen(ShieldTemplateService.SECURITY_INDEX_NAME);
        logger.info("--> retrieving user");
        GetUsersResponse resp = c.prepareGetUsers("joe").get();
        assertTrue("user should exist", resp.hasUsers());

        createIndex("idx");
        ensureGreen("idx");
        // Index a document with the default test user
        client().prepareIndex("idx", "doc", "1").setSource("body", "foo").setRefresh(true).get();

        String token = basicAuthHeaderValue("joe", new SecuredString("s3krit".toCharArray()));
        SearchResponse searchResp = client().filterWithHeader(Collections.singletonMap("Authorization", token)).prepareSearch("idx").get();

        assertEquals(searchResp.getHits().getTotalHits(), 1L);
    }

    public void testUpdatingUserAndAuthentication() throws Exception {
        SecurityClient c = securityClient();
        logger.error("--> creating user");
        c.preparePutUser("joe", "s3krit".toCharArray(), ShieldSettingsSource.DEFAULT_ROLE).get();
        logger.error("--> waiting for .shield index");
        ensureGreen(ShieldTemplateService.SECURITY_INDEX_NAME);
        logger.info("--> retrieving user");
        GetUsersResponse resp = c.prepareGetUsers("joe").get();
        assertTrue("user should exist", resp.hasUsers());
        assertThat(resp.users()[0].roles(), arrayContaining(ShieldSettingsSource.DEFAULT_ROLE));

        createIndex("idx");
        ensureGreen("idx");
        // Index a document with the default test user
        client().prepareIndex("idx", "doc", "1").setSource("body", "foo").setRefresh(true).get();
        String token = basicAuthHeaderValue("joe", new SecuredString("s3krit".toCharArray()));
        SearchResponse searchResp = client().filterWithHeader(Collections.singletonMap("Authorization", token)).prepareSearch("idx").get();

        assertEquals(searchResp.getHits().getTotalHits(), 1L);

        c.preparePutUser("joe", "s3krit2".toCharArray(), ShieldSettingsSource.DEFAULT_ROLE).get();

        try {
            client().filterWithHeader(Collections.singletonMap("Authorization", token)).prepareSearch("idx").get();
            fail("authentication with old credentials after an update to the user should fail!");
        } catch (ElasticsearchSecurityException e) {
            // expected
            assertThat(e.status(), is(RestStatus.UNAUTHORIZED));
        }

        token = basicAuthHeaderValue("joe", new SecuredString("s3krit2".toCharArray()));
        searchResp = client().filterWithHeader(Collections.singletonMap("Authorization", token)).prepareSearch("idx").get();
        assertEquals(searchResp.getHits().getTotalHits(), 1L);
    }

    public void testCreateDeleteAuthenticate() {
        SecurityClient c = securityClient();
        logger.error("--> creating user");
        c.preparePutUser("joe", "s3krit".toCharArray(), ShieldSettingsSource.DEFAULT_ROLE).get();
        logger.error("--> waiting for .shield index");
        ensureGreen(ShieldTemplateService.SECURITY_INDEX_NAME);
        logger.info("--> retrieving user");
        GetUsersResponse resp = c.prepareGetUsers("joe").get();
        assertTrue("user should exist", resp.hasUsers());
        assertThat(resp.users()[0].roles(), arrayContaining(ShieldSettingsSource.DEFAULT_ROLE));

        createIndex("idx");
        ensureGreen("idx");
        // Index a document with the default test user
        client().prepareIndex("idx", "doc", "1").setSource("body", "foo").setRefresh(true).get();
        String token = basicAuthHeaderValue("joe", new SecuredString("s3krit".toCharArray()));
        SearchResponse searchResp = client().filterWithHeader(Collections.singletonMap("Authorization", token)).prepareSearch("idx").get();

        assertEquals(searchResp.getHits().getTotalHits(), 1L);

        DeleteUserResponse response = c.prepareDeleteUser("joe").get();
        assertThat(response.found(), is(true));
        try {
            client().filterWithHeader(Collections.singletonMap("Authorization", token)).prepareSearch("idx").get();
            fail("authentication with a deleted user should fail!");
        } catch (ElasticsearchSecurityException e) {
            // expected
            assertThat(e.status(), is(RestStatus.UNAUTHORIZED));
        }
    }

    public void testCreateAndUpdateRole() {
        final boolean authenticate = randomBoolean();
        SecurityClient c = securityClient();
        logger.error("--> creating role");
        c.preparePutRole("test_role")
                .cluster("all")
                .addIndices(new String[]{"*"}, new String[]{"read"},
                        new String[]{"body", "title"}, new BytesArray("{\"match_all\": {}}"))
                .get();
        logger.error("--> creating user");
        c.preparePutUser("joe", "s3krit".toCharArray(), "test_role").get();
        logger.error("--> waiting for .shield index");
        ensureGreen(ShieldTemplateService.SECURITY_INDEX_NAME);

        if (authenticate) {
            final String token = basicAuthHeaderValue("joe", new SecuredString("s3krit".toCharArray()));
            ClusterHealthResponse response = client().filterWithHeader(Collections.singletonMap("Authorization", token)).admin().cluster()
                    .prepareHealth().get();
            assertFalse(response.isTimedOut());
            c.preparePutRole("test_role")
                    .cluster("none")
                    .addIndices(new String[]{"*"}, new String[]{"read"},
                            new String[]{"body", "title"}, new BytesArray("{\"match_all\": {}}"))
                    .get();
            try {
                client().filterWithHeader(Collections.singletonMap("Authorization", token)).admin().cluster().prepareHealth().get();
                fail("user should not be able to execute any cluster actions!");
            } catch (ElasticsearchSecurityException e) {
                assertThat(e.status(), is(RestStatus.FORBIDDEN));
            }
        } else {
            GetRolesResponse getRolesResponse = c.prepareGetRoles().names("test_role").get();
            assertTrue("test_role does not exist!", getRolesResponse.hasRoles());
            assertTrue("any cluster permission should be authorized",
                    Role.builder(getRolesResponse.roles()[0]).build().cluster().check("cluster:admin/foo"));

            c.preparePutRole("test_role")
                    .cluster("none")
                    .addIndices(new String[]{"*"}, new String[]{"read"},
                            new String[]{"body", "title"}, new BytesArray("{\"match_all\": {}}"))
                    .get();
            getRolesResponse = c.prepareGetRoles().names("test_role").get();
            assertTrue("test_role does not exist!", getRolesResponse.hasRoles());

            assertFalse("no cluster permission should be authorized",
                    Role.builder(getRolesResponse.roles()[0]).build().cluster().check("cluster:admin/bar"));
        }
    }

    public void testAuthenticateWithDeletedRole() {
        SecurityClient c = securityClient();
        logger.error("--> creating role");
        c.preparePutRole("test_role")
                .cluster("all")
                .addIndices(new String[]{"*"}, new String[]{"read"},
                        new String[]{"body", "title"}, new BytesArray("{\"match_all\": {}}"))
                .get();
        c.preparePutUser("joe", "s3krit".toCharArray(), "test_role").get();
        logger.error("--> waiting for .shield index");
        ensureGreen(ShieldTemplateService.SECURITY_INDEX_NAME);

        final String token = basicAuthHeaderValue("joe", new SecuredString("s3krit".toCharArray()));
        ClusterHealthResponse response = client().filterWithHeader(Collections.singletonMap("Authorization", token)).admin().cluster()
                .prepareHealth().get();
        assertFalse(response.isTimedOut());
        c.prepareDeleteRole("test_role").get();
        try {
            client().filterWithHeader(Collections.singletonMap("Authorization", token)).admin().cluster().prepareHealth().get();
            fail("user should not be able to execute any actions!");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.status(), is(RestStatus.FORBIDDEN));
        }
    }

    public void testPutUserWithoutPassword() {
        SecurityClient client = securityClient();
        // create some roles
        client.preparePutRole("admin_role")
                .cluster("all")
                .addIndices(new String[]{"*"}, new String[]{"all"}, null, null)
                .get();
        client.preparePutRole("read_role")
                .cluster("none")
                .addIndices(new String[]{"*"}, new String[]{"read"}, null, null)
                .get();

        assertThat(client.prepareGetUsers("joes").get().hasUsers(), is(false));
        // check that putting a user without a password fails if the user doesn't exist
        try {
            client.preparePutUser("joe", null, "admin_role").get();
            fail("cannot create a user without a password");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), containsString("password must be specified"));
        }

        assertThat(client.prepareGetUsers("joes").get().hasUsers(), is(false));

        // create joe with a password and verify the user works
        client.preparePutUser("joe", "changeme".toCharArray(), "admin_role").get();
        assertThat(client.prepareGetUsers("joe").get().hasUsers(), is(true));
        final String token = basicAuthHeaderValue("joe", new SecuredString("changeme".toCharArray()));
        ClusterHealthResponse response = client().filterWithHeader(Collections.singletonMap("Authorization", token)).admin().cluster()
                .prepareHealth().get();
        assertFalse(response.isTimedOut());

        // modify joe without sending the password
        client.preparePutUser("joe", null, "read_role").fullName("Joe Smith").get();
        GetUsersResponse getUsersResponse = client.prepareGetUsers("joe").get();
        assertThat(getUsersResponse.hasUsers(), is(true));
        assertThat(getUsersResponse.users().length, is(1));
        User joe = getUsersResponse.users()[0];
        assertThat(joe.roles(), arrayContaining("read_role"));
        assertThat(joe.fullName(), is("Joe Smith"));

        // test that role change took effect
        try {
            client().filterWithHeader(Collections.singletonMap("Authorization", token)).admin().cluster().prepareHealth().get();
            fail("test_role does not have permission to get health");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.getMessage(), containsString("authorized"));
        }

        // update the user with password and admin role again
        client.preparePutUser("joe", "changeme2".toCharArray(), "admin_role").fullName("Joe Smith").get();
        getUsersResponse = client.prepareGetUsers("joe").get();
        assertThat(getUsersResponse.hasUsers(), is(true));
        assertThat(getUsersResponse.users().length, is(1));
        joe = getUsersResponse.users()[0];
        assertThat(joe.roles(), arrayContaining("admin_role"));
        assertThat(joe.fullName(), is("Joe Smith"));

        // validate that joe cannot auth with the old token
        try {
            client().filterWithHeader(Collections.singletonMap("Authorization", token)).admin().cluster().prepareHealth().get();
            fail("should not authenticate with old password");
        } catch (ElasticsearchSecurityException e) {
            assertThat(e.getMessage(), containsString("authenticate"));
        }

        // test with new password and role
        response = client()
                .filterWithHeader(
                        Collections.singletonMap("Authorization",basicAuthHeaderValue("joe", new SecuredString("changeme2".toCharArray()))))
                .admin().cluster().prepareHealth().get();
        assertFalse(response.isTimedOut());
    }

    public void testCannotCreateUserWithShortPassword() throws Exception {
        SecurityClient client = securityClient();
        try {
            client.preparePutUser("joe", randomAsciiOfLengthBetween(0, 5).toCharArray(), "admin_role").get();
            fail("cannot create a user without a password < 6 characters");
        } catch (ValidationException v) {
            assertThat(v.getMessage().contains("password"), is(true));
        }
    }
}
