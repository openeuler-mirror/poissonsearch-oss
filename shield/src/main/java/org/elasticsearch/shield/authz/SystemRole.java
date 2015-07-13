/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authz;

import com.google.common.base.Predicate;

/**
 *
 */
public class SystemRole {

    public static final SystemRole INSTANCE = new SystemRole();

    public static final String NAME = "__es_system_role";

    private static final Predicate<String> PREDICATE = Privilege.SYSTEM.predicate();

    private SystemRole() {
    }

    public boolean check(String action) {
        return PREDICATE.apply(action);
    }
}
