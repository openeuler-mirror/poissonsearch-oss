/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.esusers;

import org.elasticsearch.action.Action;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.client.AdminClient;
import org.elasticsearch.client.Client;
import org.elasticsearch.client.ClusterAdminClient;
import org.elasticsearch.client.IndicesAdminClient;
import org.elasticsearch.client.support.Headers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.support.Hasher;
import org.elasticsearch.shield.authc.support.SecuredStringTests;
import org.elasticsearch.shield.authc.support.UsernamePasswordToken;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.watcher.ResourceWatcherService;
import org.junit.Before;
import org.junit.Test;

import java.util.Locale;

import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.arrayContaining;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 *
 */
public class ESUsersRealmTests extends ESTestCase {

    private Client client;
    private AdminClient adminClient;
    private FileUserPasswdStore userPasswdStore;
    private FileUserRolesStore userRolesStore;
    private Settings globalSettings;

    @Before
    public void init() throws Exception {
        client = mock(Client.class);
        adminClient = mock(AdminClient.class);
        userPasswdStore = mock(FileUserPasswdStore.class);
        userRolesStore = mock(FileUserRolesStore.class);
        globalSettings = Settings.builder().put("path.home", createTempDir()).build();
    }

    @Test
    public void testAuthenticate() throws Exception {
        when(userPasswdStore.verifyPassword("user1", SecuredStringTests.build("test123"))).thenReturn(true);
        when(userRolesStore.roles("user1")).thenReturn(new String[] { "role1", "role2" });
        RealmConfig config = new RealmConfig("esusers-test", Settings.EMPTY, globalSettings);
        ESUsersRealm realm = new ESUsersRealm(config, userPasswdStore, userRolesStore);
        User user = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        assertThat(user, notNullValue());
        assertThat(user.principal(), equalTo("user1"));
        assertThat(user.roles(), notNullValue());
        assertThat(user.roles().length, equalTo(2));
        assertThat(user.roles(), arrayContaining("role1", "role2"));
    }

    @Test
    public void testAuthenticate_Caching() throws Exception {
        Settings settings = Settings.builder()
                .put("cache.hash_algo", Hasher.values()[randomIntBetween(0, Hasher.values().length - 1)].name().toLowerCase(Locale.ROOT))
                .build();
        RealmConfig config = new RealmConfig("esusers-test", settings, globalSettings);
        when(userPasswdStore.verifyPassword("user1", SecuredStringTests.build("test123"))).thenReturn(true);
        when(userRolesStore.roles("user1")).thenReturn(new String[]{"role1", "role2"});
        ESUsersRealm realm = new ESUsersRealm(config, userPasswdStore, userRolesStore);
        User user1 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        User user2 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        assertThat(user1, sameInstance(user2));
    }

    @Test
    public void testAuthenticate_Caching_Refresh() throws Exception {
        RealmConfig config = new RealmConfig("esusers-test", Settings.EMPTY, globalSettings);
        userPasswdStore = spy(new UserPasswdStore(config));
        userRolesStore = spy(new UserRolesStore(config));
        doReturn(true).when(userPasswdStore).verifyPassword("user1", SecuredStringTests.build("test123"));
        doReturn(new String[] { "role1", "role2" }).when(userRolesStore).roles("user1");
        ESUsersRealm realm = new ESUsersRealm(config, userPasswdStore, userRolesStore);
        User user1 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        User user2 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        assertThat(user1, sameInstance(user2));
        userPasswdStore.notifyRefresh();
        User user3 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        assertThat(user2, not(sameInstance(user3)));
        User user4 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        assertThat(user3, sameInstance(user4));
        userRolesStore.notifyRefresh();
        User user5 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        assertThat(user4, not(sameInstance(user5)));
        User user6 = realm.authenticate(new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));
        assertThat(user5, sameInstance(user6));
    }

    @Test
    public void testToken() throws Exception {
        RealmConfig config = new RealmConfig("esusers-test", Settings.EMPTY, globalSettings);
        when(userPasswdStore.verifyPassword("user1", SecuredStringTests.build("test123"))).thenReturn(true);
        when(userRolesStore.roles("user1")).thenReturn(new String[]{"role1", "role2"});
        ESUsersRealm realm = new ESUsersRealm(config, userPasswdStore, userRolesStore);

        TransportRequest request = new TransportRequest() {};
        UsernamePasswordToken.putTokenHeader(request, new UsernamePasswordToken("user1", SecuredStringTests.build("test123")));

        UsernamePasswordToken token = realm.token(request);
        assertThat(token, notNullValue());
        assertThat(token.principal(), equalTo("user1"));
        assertThat(token.credentials(), notNullValue());
        assertThat(new String(token.credentials().internalChars()), equalTo("test123"));
    }

    @Test
    public void testLookup() throws Exception {
        when(userPasswdStore.userExists("user1")).thenReturn(true);
        when(userRolesStore.roles("user1")).thenReturn(new String[] { "role1", "role2" });
        RealmConfig config = new RealmConfig("esusers-test", Settings.EMPTY, globalSettings);
        ESUsersRealm realm = new ESUsersRealm(config, userPasswdStore, userRolesStore);

        User user = realm.lookupUser("user1");

        assertThat(user, notNullValue());
        assertThat(user.principal(), equalTo("user1"));
        assertThat(user.roles(), notNullValue());
        assertThat(user.roles().length, equalTo(2));
        assertThat(user.roles(), arrayContaining("role1", "role2"));
    }

    @Test
    public void testLookupCaching() throws Exception {
        when(userPasswdStore.userExists("user1")).thenReturn(true);
        when(userRolesStore.roles("user1")).thenReturn(new String[] { "role1", "role2" });
        RealmConfig config = new RealmConfig("esusers-test", Settings.EMPTY, globalSettings);
        ESUsersRealm realm = new ESUsersRealm(config, userPasswdStore, userRolesStore);

        User user = realm.lookupUser("user1");
        User user1 = realm.lookupUser("user1");
        assertThat(user, sameInstance(user1));
        verify(userPasswdStore).userExists("user1");
        verify(userRolesStore).roles("user1");
    }

    @Test
    public void testLookupCachingWithRefresh() throws Exception {
        RealmConfig config = new RealmConfig("esusers-test", Settings.EMPTY, globalSettings);
        userPasswdStore = spy(new UserPasswdStore(config));
        userRolesStore = spy(new UserRolesStore(config));
        doReturn(true).when(userPasswdStore).userExists("user1");
        doReturn(new String[] { "role1", "role2" }).when(userRolesStore).roles("user1");
        ESUsersRealm realm = new ESUsersRealm(config, userPasswdStore, userRolesStore);
        User user1 = realm.lookupUser("user1");
        User user2 = realm.lookupUser("user1");
        assertThat(user1, sameInstance(user2));
        userPasswdStore.notifyRefresh();
        User user3 = realm.lookupUser("user1");
        assertThat(user2, not(sameInstance(user3)));
        User user4 = realm.lookupUser("user1");
        assertThat(user3, sameInstance(user4));
        userRolesStore.notifyRefresh();
        User user5 = realm.lookupUser("user1");
        assertThat(user4, not(sameInstance(user5)));
        User user6 = realm.lookupUser("user1");
        assertThat(user5, sameInstance(user6));
    }

    @Test @SuppressWarnings("unchecked")
    public void testAuthorizationHeaderIsNotCopied() throws Exception {
        RestController restController = mock(RestController.class);
        RealmConfig config = new RealmConfig("esusers-test", Settings.EMPTY, globalSettings);
        new ESUsersRealm(config, new UserPasswdStore(config), new UserRolesStore(config));
        when(restController.relevantHeaders()).thenReturn(emptySet());
        when(client.admin()).thenReturn(adminClient);
        when(client.settings()).thenReturn(Settings.EMPTY);
        when(client.headers()).thenReturn(Headers.EMPTY);
        when(adminClient.cluster()).thenReturn(mock(ClusterAdminClient.class));
        when(adminClient.indices()).thenReturn(mock(IndicesAdminClient.class));
        final ActionRequest request = new ActionRequest() {
            @Override
            public ActionRequestValidationException validate() {
                return null;
            }
        };
        RestRequest restRequest = mock(RestRequest.class);
        final Action action = mock(Action.class);
        final ActionListener listener = mock(ActionListener.class);
        BaseRestHandler handler = new BaseRestHandler(Settings.EMPTY, restController, client) {
            @Override
            protected void handleRequest(RestRequest restRequest, RestChannel channel, Client client) throws Exception {
                client.execute(action, request, listener);
            }
        };

        when(restRequest.header(UsernamePasswordToken.BASIC_AUTH_HEADER)).thenReturn("foobar");
        RestChannel channel = mock(RestChannel.class);
        handler.handleRequest(restRequest, channel);
        assertThat(request.getHeader(UsernamePasswordToken.BASIC_AUTH_HEADER), is(nullValue()));
    }

    static class UserPasswdStore extends FileUserPasswdStore {

        public UserPasswdStore(RealmConfig config) {
            super(config, mock(ResourceWatcherService.class));
        }
    }

    static class UserRolesStore extends FileUserRolesStore {

        public UserRolesStore(RealmConfig config) {
            super(config, mock(ResourceWatcherService.class));
        }
    }
}
