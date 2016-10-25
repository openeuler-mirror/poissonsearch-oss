/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.action.filter;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.search.SearchScrollRequest;
import org.elasticsearch.action.support.ActionFilterChain;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.common.ContextPreservingActionListener;
import org.elasticsearch.xpack.security.SecurityContext;
import org.elasticsearch.xpack.security.audit.AuditTrailService;
import org.elasticsearch.xpack.security.authc.Authentication;
import org.elasticsearch.xpack.security.authc.Authentication.RealmRef;
import org.elasticsearch.xpack.security.authc.AuthenticationService;
import org.elasticsearch.xpack.security.authz.AuthorizationService;
import org.elasticsearch.xpack.security.user.SystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.xpack.security.crypto.CryptoService;
import org.elasticsearch.license.XPackLicenseState;
import org.junit.Before;

import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

public class SecurityActionFilterTests extends ESTestCase {
    private AuthenticationService authcService;
    private AuthorizationService authzService;
    private CryptoService cryptoService;
    private AuditTrailService auditTrail;
    private XPackLicenseState licenseState;
    private SecurityActionFilter filter;

    @Before
    public void init() throws Exception {
        authcService = mock(AuthenticationService.class);
        authzService = mock(AuthorizationService.class);
        cryptoService = mock(CryptoService.class);
        auditTrail = mock(AuditTrailService.class);
        licenseState = mock(XPackLicenseState.class);
        when(licenseState.isAuthAllowed()).thenReturn(true);
        when(licenseState.isStatsAndHealthAllowed()).thenReturn(true);
        ThreadPool threadPool = mock(ThreadPool.class);
        when(threadPool.getThreadContext()).thenReturn(new ThreadContext(Settings.EMPTY));
        filter = new SecurityActionFilter(Settings.EMPTY, authcService, authzService, cryptoService, auditTrail, licenseState,
                new HashSet<>(), threadPool, mock(SecurityContext.class));
    }

    public void testApply() throws Exception {
        ActionRequest request = mock(ActionRequest.class);
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        Task task = mock(Task.class);
        User user = new User("username", "r1", "r2");
        Authentication authentication = new Authentication(user, new RealmRef("test", "test", "foo"), null);

        when(authcService.authenticate("_action", request, SystemUser.INSTANCE)).thenReturn(authentication);
        doAnswer((i) -> {
            ActionListener callback =
                    (ActionListener) i.getArguments()[1];
            callback.onResponse(Collections.emptyList());
            return Void.TYPE;
        }).when(authzService).roles(any(User.class), any(ActionListener.class));
        doReturn(request).when(spy(filter)).unsign(user, "_action", request);
        filter.apply(task, "_action", request, listener, chain);
        verify(authzService).authorize(authentication, "_action", request, Collections.emptyList(), Collections.emptyList());
        verify(chain).proceed(eq(task), eq("_action"), eq(request), isA(ContextPreservingActionListener.class));
    }

    public void testActionProcessException() throws Exception {
        ActionRequest request = mock(ActionRequest.class);
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        RuntimeException exception = new RuntimeException("process-error");
        Task task = mock(Task.class);
        User user = new User("username", "r1", "r2");
        Authentication authentication = new Authentication(user, new RealmRef("test", "test", "foo"), null);
        when(authcService.authenticate("_action", request, SystemUser.INSTANCE)).thenReturn(authentication);
        doAnswer((i) -> {
            ActionListener callback =
                    (ActionListener) i.getArguments()[1];
            callback.onResponse(Collections.emptyList());
            return Void.TYPE;
        }).when(authzService).roles(any(User.class), any(ActionListener.class));
        doThrow(exception).when(authzService).authorize(eq(authentication), eq("_action"), eq(request), any(Collection.class),
                any(Collection.class));
        filter.apply(task, "_action", request, listener, chain);
        verify(listener).onFailure(exception);
        verifyNoMoreInteractions(chain);
    }

    public void testActionSignature() throws Exception {
        SearchScrollRequest request = new SearchScrollRequest("signed_scroll_id");
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        User user = mock(User.class);
        Task task = mock(Task.class);
        Authentication authentication = new Authentication(user, new RealmRef("test", "test", "foo"), null);
        when(authcService.authenticate("_action", request, SystemUser.INSTANCE)).thenReturn(authentication);
        when(cryptoService.isSigned("signed_scroll_id")).thenReturn(true);
        when(cryptoService.unsignAndVerify("signed_scroll_id")).thenReturn("scroll_id");
        doAnswer((i) -> {
            ActionListener callback =
                    (ActionListener) i.getArguments()[1];
            callback.onResponse(Collections.emptyList());
            return Void.TYPE;
        }).when(authzService).roles(any(User.class), any(ActionListener.class));
        filter.apply(task, "_action", request, listener, chain);
        assertThat(request.scrollId(), equalTo("scroll_id"));

        verify(authzService).authorize(authentication, "_action", request, Collections.emptyList(), Collections.emptyList());
        verify(chain).proceed(eq(task), eq("_action"), eq(request), isA(ContextPreservingActionListener.class));
    }

    public void testActionSignatureError() throws Exception {
        SearchScrollRequest request = new SearchScrollRequest("scroll_id");
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        IllegalArgumentException sigException = new IllegalArgumentException("bad bad boy");
        User user = mock(User.class);
        Task task = mock(Task.class);
        Authentication authentication = new Authentication(user, new RealmRef("test", "test", "foo"), null);
        when(authcService.authenticate("_action", request, SystemUser.INSTANCE)).thenReturn(authentication);
        when(cryptoService.isSigned("scroll_id")).thenReturn(true);
        doThrow(sigException).when(cryptoService).unsignAndVerify("scroll_id");
        doAnswer((i) -> {
            ActionListener callback =
                    (ActionListener) i.getArguments()[1];
            callback.onResponse(Collections.emptyList());
            return Void.TYPE;
        }).when(authzService).roles(any(User.class), any(ActionListener.class));
        filter.apply(task, "_action", request, listener, chain);
        verify(listener).onFailure(isA(ElasticsearchSecurityException.class));
        verify(auditTrail).tamperedRequest(user, "_action", request);
        verifyNoMoreInteractions(chain);
    }

    public void testApplyUnlicensed() throws Exception {
        ActionRequest request = mock(ActionRequest.class);
        ActionListener listener = mock(ActionListener.class);
        ActionFilterChain chain = mock(ActionFilterChain.class);
        Task task = mock(Task.class);
        when(licenseState.isAuthAllowed()).thenReturn(false);
        filter.apply(task, "_action", request, listener, chain);
        verifyZeroInteractions(authcService);
        verifyZeroInteractions(authzService);
        verify(chain).proceed(eq(task), eq("_action"), eq(request), eq(listener));
    }

}
