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
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.security.authc.esnative.NativeUsersStore;
import org.elasticsearch.xpack.security.authc.esnative.ReservedRealm;
import org.elasticsearch.xpack.security.authc.support.Hasher;
import org.elasticsearch.xpack.security.authc.support.SecuredString;
import org.elasticsearch.xpack.security.user.AnonymousUser;
import org.elasticsearch.xpack.security.user.SystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.junit.After;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

public class TransportPutUserActionTests extends ESTestCase {

    @After
    public void resetAnonymous() {
        AnonymousUser.initialize(Settings.EMPTY);
    }

    public void testAnonymousUser() {
        Settings settings = Settings.builder().put(AnonymousUser.ROLES_SETTING.getKey(), "superuser").build();
        AnonymousUser.initialize(settings);
        NativeUsersStore usersStore = mock(NativeUsersStore.class);
        TransportPutUserAction action = new TransportPutUserAction(Settings.EMPTY, mock(ThreadPool.class),
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), usersStore, mock(TransportService.class));

        PutUserRequest request = new PutUserRequest();
        request.username(AnonymousUser.INSTANCE.principal());

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<PutUserResponse> responseRef = new AtomicReference<>();
        action.doExecute(request, new ActionListener<PutUserResponse>() {
            @Override
            public void onResponse(PutUserResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), is(nullValue()));
        assertThat(throwableRef.get(), instanceOf(IllegalArgumentException.class));
        assertThat(throwableRef.get().getMessage(), containsString("is anonymous and cannot be modified"));
        verifyZeroInteractions(usersStore);
    }

    public void testSystemUser() {
        NativeUsersStore usersStore = mock(NativeUsersStore.class);
        TransportPutUserAction action = new TransportPutUserAction(Settings.EMPTY, mock(ThreadPool.class),
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), usersStore, mock(TransportService.class));

        PutUserRequest request = new PutUserRequest();
        request.username(SystemUser.INSTANCE.principal());

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<PutUserResponse> responseRef = new AtomicReference<>();
        action.doExecute(request, new ActionListener<PutUserResponse>() {
            @Override
            public void onResponse(PutUserResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), is(nullValue()));
        assertThat(throwableRef.get(), instanceOf(IllegalArgumentException.class));
        assertThat(throwableRef.get().getMessage(), containsString("is internal"));
        verifyZeroInteractions(usersStore);
    }

    public void testReservedUser() {
        final User reserved = randomFrom(ReservedRealm.users().toArray(new User[0]));
        NativeUsersStore usersStore = mock(NativeUsersStore.class);
        TransportPutUserAction action = new TransportPutUserAction(Settings.EMPTY, mock(ThreadPool.class),
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), usersStore, mock(TransportService.class));

        PutUserRequest request = new PutUserRequest();
        request.username(reserved.principal());

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<PutUserResponse> responseRef = new AtomicReference<>();
        action.doExecute(request, new ActionListener<PutUserResponse>() {
            @Override
            public void onResponse(PutUserResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), is(nullValue()));
        assertThat(throwableRef.get(), instanceOf(IllegalArgumentException.class));
        assertThat(throwableRef.get().getMessage(), containsString("is reserved and only the password"));
        verifyZeroInteractions(usersStore);
    }

    public void testValidUser() {
        final User user = new User("joe");
        NativeUsersStore usersStore = mock(NativeUsersStore.class);
        TransportPutUserAction action = new TransportPutUserAction(Settings.EMPTY, mock(ThreadPool.class),
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), usersStore, mock(TransportService.class));

        final boolean isCreate = randomBoolean();
        final PutUserRequest request = new PutUserRequest();
        request.username(user.principal());
        if (isCreate) {
            request.passwordHash(Hasher.BCRYPT.hash(new SecuredString("changeme".toCharArray())));
        }
        final boolean created = isCreate ? randomBoolean() : false; // updates should always return false for create
        doAnswer(new Answer() {
            public Void answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                assert args.length == 2;
                ActionListener<Boolean> listener = (ActionListener<Boolean>) args[1];
                listener.onResponse(created);
                return null;
            }
        }).when(usersStore).putUser(eq(request), any(ActionListener.class));

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<PutUserResponse> responseRef = new AtomicReference<>();
        action.doExecute(request, new ActionListener<PutUserResponse>() {
            @Override
            public void onResponse(PutUserResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), is(notNullValue()));
        assertThat(responseRef.get().created(), is(created));
        assertThat(throwableRef.get(), is(nullValue()));
        verify(usersStore, times(1)).putUser(eq(request), any(ActionListener.class));
    }

    public void testException() {
        final Exception e = randomFrom(new ElasticsearchSecurityException(""), new IllegalStateException(), new ValidationException());
        final User user = new User("joe");
        NativeUsersStore usersStore = mock(NativeUsersStore.class);
        TransportPutUserAction action = new TransportPutUserAction(Settings.EMPTY, mock(ThreadPool.class),
                mock(ActionFilters.class), mock(IndexNameExpressionResolver.class), usersStore, mock(TransportService.class));

        final PutUserRequest request = new PutUserRequest();
        request.username(user.principal());
        doAnswer(new Answer() {
            public Void answer(InvocationOnMock invocation) {
                Object[] args = invocation.getArguments();
                assert args.length == 2;
                ActionListener<Boolean> listener = (ActionListener<Boolean>) args[1];
                listener.onFailure(e);
                return null;
            }
        }).when(usersStore).putUser(eq(request), any(ActionListener.class));

        final AtomicReference<Throwable> throwableRef = new AtomicReference<>();
        final AtomicReference<PutUserResponse> responseRef = new AtomicReference<>();
        action.doExecute(request, new ActionListener<PutUserResponse>() {
            @Override
            public void onResponse(PutUserResponse response) {
                responseRef.set(response);
            }

            @Override
            public void onFailure(Exception e) {
                throwableRef.set(e);
            }
        });

        assertThat(responseRef.get(), is(nullValue()));
        assertThat(throwableRef.get(), is(notNullValue()));
        assertThat(throwableRef.get(), sameInstance(e));
        verify(usersStore, times(1)).putUser(eq(request), any(ActionListener.class));
    }
}
