/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.client;

import org.elasticsearch.action.ActionFuture;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.shield.action.admin.role.AddRoleAction;
import org.elasticsearch.shield.action.admin.role.AddRoleRequest;
import org.elasticsearch.shield.action.admin.role.AddRoleRequestBuilder;
import org.elasticsearch.shield.action.admin.role.AddRoleResponse;
import org.elasticsearch.shield.action.admin.role.DeleteRoleAction;
import org.elasticsearch.shield.action.admin.role.DeleteRoleRequest;
import org.elasticsearch.shield.action.admin.role.DeleteRoleRequestBuilder;
import org.elasticsearch.shield.action.admin.role.DeleteRoleResponse;
import org.elasticsearch.shield.action.admin.role.GetRolesAction;
import org.elasticsearch.shield.action.admin.role.GetRolesRequest;
import org.elasticsearch.shield.action.admin.role.GetRolesRequestBuilder;
import org.elasticsearch.shield.action.admin.role.GetRolesResponse;
import org.elasticsearch.shield.action.admin.user.AddUserAction;
import org.elasticsearch.shield.action.admin.user.AddUserRequest;
import org.elasticsearch.shield.action.admin.user.AddUserRequestBuilder;
import org.elasticsearch.shield.action.admin.user.AddUserResponse;
import org.elasticsearch.shield.action.admin.user.DeleteUserAction;
import org.elasticsearch.shield.action.admin.user.DeleteUserRequest;
import org.elasticsearch.shield.action.admin.user.DeleteUserRequestBuilder;
import org.elasticsearch.shield.action.admin.user.DeleteUserResponse;
import org.elasticsearch.shield.action.admin.user.GetUsersAction;
import org.elasticsearch.shield.action.admin.user.GetUsersRequest;
import org.elasticsearch.shield.action.admin.user.GetUsersRequestBuilder;
import org.elasticsearch.shield.action.admin.user.GetUsersResponse;
import org.elasticsearch.shield.action.authc.cache.ClearRealmCacheAction;
import org.elasticsearch.shield.action.authc.cache.ClearRealmCacheRequest;
import org.elasticsearch.shield.action.authc.cache.ClearRealmCacheRequestBuilder;
import org.elasticsearch.shield.action.authc.cache.ClearRealmCacheResponse;
import org.elasticsearch.shield.action.authz.cache.ClearRolesCacheAction;
import org.elasticsearch.shield.action.authz.cache.ClearRolesCacheRequest;
import org.elasticsearch.shield.action.authz.cache.ClearRolesCacheRequestBuilder;
import org.elasticsearch.shield.action.authz.cache.ClearRolesCacheResponse;

/**
 * A wrapper to elasticsearch clients that exposes all Shield related APIs
 */
public class ShieldClient {

    private final ElasticsearchClient client;
    private final ShieldAuthcClient authcClient;

    public ShieldClient(ElasticsearchClient client) {
        this.client = client;
        this.authcClient = new ShieldAuthcClient(client);
    }

    @Deprecated
    public ShieldAuthcClient authc() {
        return authcClient;
    }

    /****************
     * authc things *
     ****************/

    /**
     * Clears the realm caches. It's possible to clear all user entries from all realms in the cluster or alternatively
     * select the realms (by their unique names) and/or users (by their usernames) that should be evicted.
     */
    @SuppressWarnings("unchecked")
    public ClearRealmCacheRequestBuilder prepareClearRealmCache() {
        return new ClearRealmCacheRequestBuilder(client);
    }

    /**
     * Clears the realm caches. It's possible to clear all user entries from all realms in the cluster or alternatively
     * select the realms (by their unique names) and/or users (by their usernames) that should be evicted.
     */
    @SuppressWarnings("unchecked")
    public void clearRealmCache(ClearRealmCacheRequest request, ActionListener<ClearRealmCacheResponse> listener) {
        client.execute(ClearRealmCacheAction.INSTANCE, request, listener);
    }

    /**
     * Clears the realm caches. It's possible to clear all user entries from all realms in the cluster or alternatively
     * select the realms (by their unique names) and/or users (by their usernames) that should be evicted.
     */
    @SuppressWarnings("unchecked")
    public ActionFuture<ClearRealmCacheResponse> clearRealmCache(ClearRealmCacheRequest request) {
        return client.execute(ClearRealmCacheAction.INSTANCE, request);
    }

    /****************
     * authz things *
     ****************/

    /**
     * Clears the roles cache. This API only works for the naitve roles that are stored in an elasticsearch index. It is
     * possible to clear the cache of all roles or to specify the names of individual roles that should have their cache
     * cleared.
     */
    public ClearRolesCacheRequestBuilder prepareClearRolesCache() {
        return new ClearRolesCacheRequestBuilder(client);
    }

    /**
     * Clears the roles cache. This API only works for the naitve roles that are stored in an elasticsearch index. It is
     * possible to clear the cache of all roles or to specify the names of individual roles that should have their cache
     * cleared.
     */
    public void clearRolesCache(ClearRolesCacheRequest request, ActionListener<ClearRolesCacheResponse> listener) {
        client.execute(ClearRolesCacheAction.INSTANCE, request, listener);
    }

    /**
     * Clears the roles cache. This API only works for the naitve roles that are stored in an elasticsearch index. It is
     * possible to clear the cache of all roles or to specify the names of individual roles that should have their cache
     * cleared.
     */
    public ActionFuture<ClearRolesCacheResponse> clearRolesCache(ClearRolesCacheRequest request) {
        return client.execute(ClearRolesCacheAction.INSTANCE, request);
    }

    /****************
     * admin things *
     ****************/

    public GetUsersRequestBuilder prepareGetUsers() {
        return new GetUsersRequestBuilder(client);
    }

    public void getUsers(GetUsersRequest request, ActionListener<GetUsersResponse> listener) {
        client.execute(GetUsersAction.INSTANCE, request, listener);
    }

    public DeleteUserRequestBuilder prepareDeleteUser() {
        return new DeleteUserRequestBuilder(client);
    }

    public void deleteUser(DeleteUserRequest request, ActionListener<DeleteUserResponse> listener) {
        client.execute(DeleteUserAction.INSTANCE, request, listener);
    }

    public AddUserRequestBuilder prepareAddUser() {
        return new AddUserRequestBuilder(client);
    }

    public void addUser(AddUserRequest request, ActionListener<AddUserResponse> listener) {
        client.execute(AddUserAction.INSTANCE, request, listener);
    }

    public GetRolesRequestBuilder prepareGetRoles() {
        return new GetRolesRequestBuilder(client);
    }

    public void getRoles(GetRolesRequest request, ActionListener<GetRolesResponse> listener) {
        client.execute(GetRolesAction.INSTANCE, request, listener);
    }

    public DeleteRoleRequestBuilder prepareDeleteRole() {
        return new DeleteRoleRequestBuilder(client);
    }

    public void deleteRole(DeleteRoleRequest request, ActionListener<DeleteRoleResponse> listener) {
        client.execute(DeleteRoleAction.INSTANCE, request, listener);
    }

    public AddRoleRequestBuilder prepareAddRole() {
        return new AddRoleRequestBuilder(client);
    }

    public void addRole(AddRoleRequest request, ActionListener<AddRoleResponse> listener) {
        client.execute(AddRoleAction.INSTANCE, request, listener);
    }
}
