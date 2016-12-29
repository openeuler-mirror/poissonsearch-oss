/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz.store;

import org.elasticsearch.xpack.security.SecurityContext;
import org.elasticsearch.xpack.security.authz.permission.IngestAdminRole;
import org.elasticsearch.xpack.security.authz.permission.KibanaRole;
import org.elasticsearch.xpack.security.authz.permission.KibanaUserRole;
import org.elasticsearch.xpack.security.authz.permission.LogstashSystemRole;
import org.elasticsearch.xpack.security.authz.permission.MonitoringUserRole;
import org.elasticsearch.xpack.security.authz.permission.RemoteMonitoringAgentRole;
import org.elasticsearch.xpack.security.authz.permission.ReportingUserRole;
import org.elasticsearch.xpack.security.authz.permission.SuperuserRole;
import org.elasticsearch.xpack.security.authz.permission.TransportClientRole;
import org.elasticsearch.xpack.security.user.ElasticUser;
import org.elasticsearch.xpack.security.user.KibanaUser;
import org.elasticsearch.xpack.security.user.SystemUser;
import org.elasticsearch.xpack.security.user.User;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the {@link ReservedRolesStore}
 */
public class ReservedRolesStoreTests extends ESTestCase {

    private final User user = new User("joe");
    private SecurityContext securityContext;
    private ReservedRolesStore reservedRolesStore;

    @Before
    public void setupMocks() {
        securityContext = mock(SecurityContext.class);
        when(securityContext.getUser()).thenReturn(user);
        reservedRolesStore = new ReservedRolesStore();
    }

    public void testRetrievingReservedRoles() {
        assertThat(reservedRolesStore.role(SuperuserRole.NAME), sameInstance(SuperuserRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(SuperuserRole.NAME), sameInstance(SuperuserRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(TransportClientRole.NAME), sameInstance(TransportClientRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(TransportClientRole.NAME), sameInstance(TransportClientRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(KibanaUserRole.NAME), sameInstance(KibanaUserRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(KibanaUserRole.NAME), sameInstance(KibanaUserRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(KibanaRole.NAME), sameInstance(KibanaRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(KibanaRole.NAME), sameInstance(KibanaRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(IngestAdminRole.NAME), sameInstance(IngestAdminRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(IngestAdminRole.NAME), sameInstance(IngestAdminRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(MonitoringUserRole.NAME), sameInstance(MonitoringUserRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(MonitoringUserRole.NAME), sameInstance(MonitoringUserRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(RemoteMonitoringAgentRole.NAME), sameInstance(RemoteMonitoringAgentRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(RemoteMonitoringAgentRole.NAME), sameInstance(RemoteMonitoringAgentRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(ReportingUserRole.NAME), sameInstance(ReportingUserRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(ReportingUserRole.NAME), sameInstance(ReportingUserRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(LogstashSystemRole.NAME), sameInstance(LogstashSystemRole.INSTANCE));
        assertThat(reservedRolesStore.roleDescriptor(LogstashSystemRole.NAME), sameInstance(LogstashSystemRole.DESCRIPTOR));

        assertThat(reservedRolesStore.roleDescriptors(), contains(SuperuserRole.DESCRIPTOR, TransportClientRole.DESCRIPTOR,
                KibanaUserRole.DESCRIPTOR, KibanaRole.DESCRIPTOR, MonitoringUserRole.DESCRIPTOR, RemoteMonitoringAgentRole.DESCRIPTOR,
                IngestAdminRole.DESCRIPTOR, ReportingUserRole.DESCRIPTOR, LogstashSystemRole.DESCRIPTOR));

        assertThat(reservedRolesStore.role(SystemUser.ROLE_NAME), nullValue());
    }

    public void testIsReserved() {
        assertThat(ReservedRolesStore.isReserved(KibanaRole.NAME), is(true));
        assertThat(ReservedRolesStore.isReserved(SuperuserRole.NAME), is(true));
        assertThat(ReservedRolesStore.isReserved("foobar"), is(false));
        assertThat(ReservedRolesStore.isReserved(SystemUser.ROLE_NAME), is(true));
        assertThat(ReservedRolesStore.isReserved(TransportClientRole.NAME), is(true));
        assertThat(ReservedRolesStore.isReserved(KibanaUserRole.NAME), is(true));
        assertThat(ReservedRolesStore.isReserved(IngestAdminRole.NAME), is(true));
        assertThat(ReservedRolesStore.isReserved(RemoteMonitoringAgentRole.NAME), is(true));
        assertThat(ReservedRolesStore.isReserved(MonitoringUserRole.NAME), is(true));
        assertThat(ReservedRolesStore.isReserved(ReportingUserRole.NAME), is(true));
    }
}
