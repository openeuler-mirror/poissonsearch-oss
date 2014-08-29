/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc;

import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.audit.AuditTrail;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.elasticsearch.transport.TransportMessage;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static org.elasticsearch.shield.test.ShieldAssertions.assertContainsWWWAuthenticateHeader;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;


/**
 *
 */
public class InternalAuthenticationServiceTests extends ElasticsearchTestCase {

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    InternalAuthenticationService service;
    TransportMessage message;
    RestRequest restRequest;
    Realm firstRealm;
    Realm secondRealm;
    AuditTrail auditTrail;
    AuthenticationToken token;

    @Before
    public void init() throws Exception {
        token = mock(AuthenticationToken.class);
        message = new InternalMessage();
        restRequest = mock(RestRequest.class);
        firstRealm = mock(Realm.class);
        when(firstRealm.type()).thenReturn("first");
        secondRealm = mock(Realm.class);
        when(secondRealm.type()).thenReturn("second");
        Realms realms = mock(Realms.class);
        when(realms.realms()).thenReturn(new Realm[] {firstRealm, secondRealm});

        auditTrail = mock(AuditTrail.class);
        service = new InternalAuthenticationService(ImmutableSettings.EMPTY, realms, auditTrail);
    }

    @Test @SuppressWarnings("unchecked")
    public void testToken_FirstMissing_SecondFound() throws Exception {
        when(firstRealm.token(message)).thenReturn(null);
        when(secondRealm.token(message)).thenReturn(token);

        AuthenticationToken result = service.token("_action", message);
        assertThat(result, notNullValue());
        assertThat(result, is(token));
        verifyZeroInteractions(auditTrail);
    }

    @Test
    public void testToken_Missing() throws Exception {
        try {
            service.token("_action", message);
            fail("expected authentication exception with missing auth token");
        } catch (AuthenticationException ae) {
            assertThat(ae.getMessage(), equalTo("Missing authentication token for request [_action]"));
            assertContainsWWWAuthenticateHeader(ae);
        }
        verify(auditTrail).anonymousAccess("_action", message);
        verifyNoMoreInteractions(auditTrail);
        assertThat(message.context().get(InternalAuthenticationService.TOKEN_CTX_KEY), nullValue());
    }

    @Test
    public void testToken_MissingWithNullDefault() throws Exception {
        try {
            service.token("_action", message, null);
            fail("expected authentication exception with missing auth token and null default token");
        } catch (AuthenticationException ae) {
            assertThat(ae.getMessage(), equalTo("Missing authentication token for request [_action]"));
        }
        verify(auditTrail).anonymousAccess("_action", message);
        verifyNoMoreInteractions(auditTrail);
        assertThat(message.context().get(InternalAuthenticationService.TOKEN_CTX_KEY), nullValue());
    }

    @Test
    public void testToken_MissingWithDefault() throws Exception {
        AuthenticationToken result = service.token("_action", message, token);
        assertThat(result, notNullValue());
        assertThat(result, is(token));
        verifyZeroInteractions(auditTrail);
        assertThat(message.context().get(InternalAuthenticationService.TOKEN_CTX_KEY), notNullValue());
        assertThat(message.context().get(InternalAuthenticationService.TOKEN_CTX_KEY), is((Object) token));
    }

    @Test @SuppressWarnings("unchecked")
    public void testToken_Cached() throws Exception {
        message.context().put(InternalAuthenticationService.TOKEN_CTX_KEY, token);
        AuthenticationToken result = service.token("_action", message, token);
        assertThat(result, notNullValue());
        assertThat(result, is(token));
        verifyZeroInteractions(auditTrail);
        verifyZeroInteractions(firstRealm);
        verifyZeroInteractions(secondRealm);
        assertThat(message.context().get(InternalAuthenticationService.TOKEN_CTX_KEY), notNullValue());
        assertThat(message.context().get(InternalAuthenticationService.TOKEN_CTX_KEY), is((Object) token));
    }

    @Test @SuppressWarnings("unchecked")
    public void testAuthenticate_BothSupport_SecondSucceeds() throws Exception {
        User user = new User.Simple("_username", "r1");
        when(firstRealm.supports(token)).thenReturn(true);
        when(firstRealm.authenticate(token)).thenReturn(null); // first fails
        when(secondRealm.supports(token)).thenReturn(true);
        when(secondRealm.authenticate(token)).thenReturn(user);

        User result = service.authenticate("_action", message, token);
        assertThat(result, notNullValue());
        assertThat(result, is(user));
        verify(auditTrail).authenticationFailed("first", token, "_action", message);
        assertThat(message.context().get(InternalAuthenticationService.USER_CTX_KEY), notNullValue());
        assertThat(message.context().get(InternalAuthenticationService.USER_CTX_KEY), is((Object) user));
    }

    @Test @SuppressWarnings("unchecked")
    public void testAuthenticate_FirstNotSupporting_SecondSucceeds() throws Exception {
        User user = new User.Simple("_username", "r1");
        when(firstRealm.supports(token)).thenReturn(false);
        when(secondRealm.supports(token)).thenReturn(true);
        when(secondRealm.authenticate(token)).thenReturn(user);

        User result = service.authenticate("_action", message, token);
        assertThat(result, notNullValue());
        assertThat(result, is(user));
        verifyZeroInteractions(auditTrail);
        verify(firstRealm, never()).authenticate(token);
        assertThat(message.context().get(InternalAuthenticationService.USER_CTX_KEY), notNullValue());
        assertThat(message.context().get(InternalAuthenticationService.USER_CTX_KEY), is((Object) user));
    }

    @Test @SuppressWarnings("unchecked")
    public void testAuthenticate_Cached() throws Exception {
        User user = new User.Simple("_username", "r1");
        message.context().put(InternalAuthenticationService.USER_CTX_KEY, user);
        User result = service.authenticate("_action", message, token);
        assertThat(result, notNullValue());
        assertThat(result, is(user));
        verifyZeroInteractions(auditTrail);
        verifyZeroInteractions(firstRealm);
        verifyZeroInteractions(secondRealm);
        assertThat(message.context().get(InternalAuthenticationService.USER_CTX_KEY), notNullValue());
        assertThat(message.context().get(InternalAuthenticationService.USER_CTX_KEY), is((Object) user));
    }

    @Test
    public void testVerifyToken_Exists() throws Exception {
        when(firstRealm.hasToken(restRequest)).thenReturn(false);
        when(secondRealm.hasToken(restRequest)).thenReturn(true);
        service.verifyToken(restRequest);
    }

    @Test
    public void testVerifyToken_Missing() throws Exception {
        thrown.expect(AuthenticationException.class);
        thrown.expectMessage("Missing authentication token");
        when(firstRealm.hasToken(restRequest)).thenReturn(false);
        when(secondRealm.hasToken(restRequest)).thenReturn(false);
        service.verifyToken(restRequest);
    }

    private static class InternalMessage extends TransportMessage<InternalMessage> {
    }

}
