/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz.privilege;

import org.elasticsearch.shield.support.AutomatonPredicate;

import java.util.function.Predicate;

import static org.elasticsearch.shield.support.Automatons.patterns;

/**
 *
 */
public class SystemPrivilege extends Privilege<SystemPrivilege> {

    public static SystemPrivilege INSTANCE = new SystemPrivilege();

    protected static final Predicate<String> PREDICATE = new AutomatonPredicate(patterns(
            "internal:*",
            "indices:monitor/*", // added for marvel
            "cluster:monitor/*",  // added for marvel
            "cluster:admin/reroute", // added for DiskThresholdDecider.DiskListener
            "indices:admin/mapping/put" // ES 2.0 MappingUpdatedAction - updateMappingOnMasterSynchronously
    ));

    SystemPrivilege() {
        super(new Name("internal"));
    }

    @Override
    public Predicate<String> predicate() {
        return PREDICATE;
    }

    @Override
    public boolean implies(SystemPrivilege other) {
        return true;
    }
}
