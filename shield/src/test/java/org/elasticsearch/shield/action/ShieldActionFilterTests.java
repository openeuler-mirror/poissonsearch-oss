/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.action;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.action.interceptor.RequestInterceptor;
import org.elasticsearch.shield.audit.AuditTrail;
import org.elasticsearch.shield.authc.AuthenticationService;
import org.elasticsearch.shield.authz.AuthorizationService;
import org.elasticsearch.shield.crypto.CryptoService;
import org.elasticsearch.shield.license.ShieldLicenseState;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;
import org.junit.Test;

import java.util.HashSet;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.*;

/**
 *
 */
public class ShieldActionFilterTests extends ESTestCase {

    private AuthenticationService authcService;
    private AuthorizationService authzService;
    private CryptoService cryptoService;
    private AuditTrail auditTrail;
    private ShieldLicenseState shieldLicenseState;
    private ShieldActionFilter filter;

    @Before
    public void init() throws Exception {
        authcService = mock(AuthenticationService.class);
        authzService = mock(AuthorizationService.class);
        cryptoService = mock(CryptoService.class);
        auditTrail = mock(AuditTrail.class);
        shieldLicenseState = mock(ShieldLicenseState.class);
        when(shieldLicenseState.securityEnabled()).thenReturn(true);
        when(shieldLicenseState.statsAndHealthEnabled()).thenReturn(true);
        filter = new ShieldActionFilter(Settings.EMPTY, authcService, authzService, cryptoService, auditTrail, shieldLicenseState, new ShieldActionMapper(), new HashSet<RequestInterceptor>());
    }

    @Test
    public void testApply() throws Exception {
        ActionRequest request = mock(ActionRequest.class);
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        User user = new User.Simple("username", new String[] { "r1", "r2" });
        when(authcService.authenticate("_action", request, User.SYSTEM)).thenReturn(user);
        doReturn(request).when(spy(filter)).unsign(user, "_action", request);
        filter.apply("_action", request, listener, chain);
        verify(authzService).authorize(user, "_action", request);
        verify(chain).proceed(eq("_action"), eq(request), isA(ShieldActionFilter.SigningListener.class));
    }

    @Test
    public void testAction_Process_Exception() throws Exception {
        ActionRequest request = mock(ActionRequest.class);
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        RuntimeException exception = new RuntimeException("process-error");
        User user = new User.Simple("username", new String[] { "r1", "r2" });
        when(authcService.authenticate("_action", request, User.SYSTEM)).thenReturn(user);
        doThrow(exception).when(authzService).authorize(user, "_action", request);
        filter.apply("_action", request, listener, chain);
        verify(listener).onFailure(exception);
        verifyNoMoreInteractions(chain);
    }

    @Test
    public void testAction_Signature() throws Exception {
        SearchScrollRequest request = new SearchScrollRequest("signed_scroll_id");
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        User user = mock(User.class);
        when(authcService.authenticate("_action", request, User.SYSTEM)).thenReturn(user);
        when(cryptoService.signed("signed_scroll_id")).thenReturn(true);
        when(cryptoService.unsignAndVerify("signed_scroll_id")).thenReturn("scroll_id");
        filter.apply("_action", request, listener, chain);
        assertThat(request.scrollId(), equalTo("scroll_id"));
        verify(authzService).authorize(user, "_action", request);
        verify(chain).proceed(eq("_action"), eq(request), isA(ShieldActionFilter.SigningListener.class));
    }

    @Test
    public void testAction_SignatureError() throws Exception {
        SearchScrollRequest request = new SearchScrollRequest("scroll_id");
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        IllegalArgumentException sigException = new IllegalArgumentException("bad bad boy");
        User user = mock(User.class);
        when(authcService.authenticate("_action", request, User.SYSTEM)).thenReturn(user);
        when(cryptoService.signed("scroll_id")).thenReturn(true);
        doThrow(sigException).when(cryptoService).unsignAndVerify("scroll_id");
        filter.apply("_action", request, listener, chain);
        verify(listener).onFailure(isA(ElasticsearchSecurityException.class));
        verify(auditTrail).tamperedRequest(user, "_action", request);
        verifyNoMoreInteractions(chain);
    }

    @Test
    public void testApplyUnlicensed() throws Exception {
        ActionRequest request = mock(ActionRequest.class);
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        when(shieldLicenseState.securityEnabled()).thenReturn(false);
        filter.apply("_action", request, listener, chain);
        verifyZeroInteractions(authcService);
        verifyZeroInteractions(authzService);
        verify(chain).proceed(eq("_action"), eq(request), eq(listener));
    }

}
