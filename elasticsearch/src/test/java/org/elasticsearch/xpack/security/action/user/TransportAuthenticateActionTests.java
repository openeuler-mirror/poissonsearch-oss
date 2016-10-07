/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.action.user;

import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.security.SecurityContext;
import org.elasticsearch.xpack.security.user.ElasticUser;
import org.elasticsearch.xpack.security.user.KibanaUser;
import org.elasticsearch.xpack.security.user.SystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.xpack.security.user.XPackUser;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TransportAuthenticateActionTests extends ESTestCase {

    public void testInternalUser() {
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUser()).thenReturn(randomFrom(SystemUser.INSTANCE, XPackUser.INSTANCE));
        TransportService transportService = new TransportService(Settings.EMPTY, null, null, TransportService.NOOP_TRANSPORT_INTERCEPTOR,
                null);
        TransportAuthenticateAction action = new TransportAuthenticateAction(Settings.EMPTY, mock(ThreadPool.class), transportService,
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), securityContext);

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<AuthenticateResponse> responseRef = new AtomicReference<>();
        action.doExecute(new AuthenticateRequest(), new ActionListener<AuthenticateResponse>() {
            @Override
            public void onResponse(AuthenticateResponse authenticateResponse) {
                responseRef.set(authenticateResponse);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), nullValue());
        assertThat(throwableRef.get(), instanceOf(IllegalArgumentException.class));
        assertThat(throwableRef.get().getMessage(), containsString("is internal"));
    }

    public void testNullUser() {
        SecurityContext securityContext = mock(SecurityContext.class);
        TransportService transportService = new TransportService(Settings.EMPTY, null, null, TransportService.NOOP_TRANSPORT_INTERCEPTOR,
                null);
        TransportAuthenticateAction action = new TransportAuthenticateAction(Settings.EMPTY, mock(ThreadPool.class), transportService,
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), securityContext);

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<AuthenticateResponse> responseRef = new AtomicReference<>();
        action.doExecute(new AuthenticateRequest(), new ActionListener<AuthenticateResponse>() {
            @Override
            public void onResponse(AuthenticateResponse authenticateResponse) {
                responseRef.set(authenticateResponse);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), nullValue());
        assertThat(throwableRef.get(), instanceOf(ElasticsearchSecurityException.class));
        assertThat(throwableRef.get().getMessage(), containsString("did not find an authenticated user"));
    }

    public void testValidUser() {
        final User user = randomFrom(new ElasticUser(true), new KibanaUser(true), new User("joe"));
        SecurityContext securityContext = mock(SecurityContext.class);
        when(securityContext.getUser()).thenReturn(user);
        TransportService transportService = new TransportService(Settings.EMPTY, null, null, TransportService.NOOP_TRANSPORT_INTERCEPTOR,
                null);
        TransportAuthenticateAction action = new TransportAuthenticateAction(Settings.EMPTY, mock(ThreadPool.class), transportService,
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), securityContext);

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<AuthenticateResponse> responseRef = new AtomicReference<>();
        action.doExecute(new AuthenticateRequest(), new ActionListener<AuthenticateResponse>() {
            @Override
            public void onResponse(AuthenticateResponse authenticateResponse) {
                responseRef.set(authenticateResponse);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), notNullValue());
        assertThat(responseRef.get().user(), sameInstance(user));
        assertThat(throwableRef.get(), nullValue());
    }
}
