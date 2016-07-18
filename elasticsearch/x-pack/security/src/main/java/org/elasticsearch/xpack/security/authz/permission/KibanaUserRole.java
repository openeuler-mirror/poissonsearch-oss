/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz.permission;

import org.elasticsearch.xpack.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.security.authz.privilege.ClusterPrivilege;
import org.elasticsearch.xpack.security.authz.privilege.Privilege.Name;
import org.elasticsearch.xpack.security.support.MetadataUtils;

public class KibanaUserRole extends Role {

    private static final String[] CLUSTER_PRIVILEGES = new String[] { "monitor" };
    private static final RoleDescriptor.IndicesPrivileges[] INDICES_PRIVILEGES = new RoleDescriptor.IndicesPrivileges[] {
            RoleDescriptor.IndicesPrivileges.builder().indices(".kibana*").privileges("manage", "read", "index", "delete").build() };

    public static final String NAME = "kibana_user";
    public static final RoleDescriptor DESCRIPTOR =
            new RoleDescriptor(NAME, CLUSTER_PRIVILEGES, INDICES_PRIVILEGES, null, MetadataUtils.DEFAULT_RESERVED_METADATA);
    public static final KibanaUserRole INSTANCE = new KibanaUserRole();

    private KibanaUserRole() {
        super(DESCRIPTOR.getName(),
                new ClusterPermission.Core(ClusterPrivilege.get(new Name(DESCRIPTOR.getClusterPrivileges()))),
                new IndicesPermission.Core(Role.Builder.convertFromIndicesPrivileges(DESCRIPTOR.getIndicesPrivileges())),
                RunAsPermission.Core.NONE);
    }
}
