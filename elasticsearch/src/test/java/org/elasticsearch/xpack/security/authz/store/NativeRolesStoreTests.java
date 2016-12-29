/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz.store;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.support.PlainActionFuture;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.get.GetResult;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.security.SecurityTemplateService;
import org.elasticsearch.xpack.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.security.authz.permission.Role;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.xpack.security.test.SecurityTestUtils.getClusterStateWithSecurityIndex;
import static org.mockito.Mockito.mock;

public class NativeRolesStoreTests extends ESTestCase {

    // test that we can read a role where field permissions are stored in 2.x format (fields:...)
    public void testBWCFieldPermissions() throws IOException {
        Path path = getDataPath("roles2xformat.json");
        byte[] bytes = Files.readAllBytes(path);
        String roleString = new String(bytes, Charset.defaultCharset());
        RoleDescriptor role = NativeRolesStore.transformRole("role1", new BytesArray(roleString), logger);
        RoleDescriptor.IndicesPrivileges indicesPrivileges = role.getIndicesPrivileges()[0];
        assertTrue(indicesPrivileges.getFieldPermissions().grantsAccessTo("foo"));
        assertTrue(indicesPrivileges.getFieldPermissions().grantsAccessTo("boo"));
    }

    public void testNegativeLookupsAreCached() {
        final InternalClient internalClient = mock(InternalClient.class);
        final AtomicBoolean methodCalled = new AtomicBoolean(false);
        final NativeRolesStore rolesStore = new NativeRolesStore(Settings.EMPTY, internalClient) {
            @Override
            public State state() {
                return State.STARTED;
            }

            @Override
            void executeGetRoleRequest(String role, ActionListener<GetResponse> listener) {
                if (methodCalled.compareAndSet(false, true)) {
                    listener.onResponse(new GetResponse(new GetResult(SecurityTemplateService.SECURITY_INDEX_NAME, "role",
                            role, -1, false, BytesArray.EMPTY, Collections.emptyMap())));
                } else {
                    fail("method called more than once!");
                }
            }
        };

        // setup the roles store so the security index exists
        rolesStore.clusterChanged(new ClusterChangedEvent("negative_lookups", getClusterStateWithSecurityIndex(), getEmptyClusterState()));

        final String roleName = randomAsciiOfLengthBetween(1, 10);
        PlainActionFuture<Role> future = new PlainActionFuture<>();
        rolesStore.role(roleName, future);
        Role role = future.actionGet();
        assertTrue(methodCalled.get());
        assertNull(role);

        final int numberOfRetries = scaledRandomIntBetween(1, 20);
        for (int i = 0; i < numberOfRetries; i++) {
            future = new PlainActionFuture<>();
            rolesStore.role(roleName, future);
            role = future.actionGet();
            assertTrue(methodCalled.get());
            assertNull(role);
        }
    }

    private ClusterState getEmptyClusterState() {
        return ClusterState.builder(new ClusterName(NativeRolesStoreTests.class.getName())).build();
    }
}
