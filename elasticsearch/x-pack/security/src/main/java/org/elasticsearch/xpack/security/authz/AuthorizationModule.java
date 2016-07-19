/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authz;

import org.elasticsearch.common.inject.util.Providers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.security.authz.store.CompositeRolesStore;
import org.elasticsearch.xpack.security.authz.store.FileRolesStore;
import org.elasticsearch.xpack.security.authz.store.NativeRolesStore;
import org.elasticsearch.xpack.security.authz.store.ReservedRolesStore;
import org.elasticsearch.xpack.security.authz.store.RolesStore;
import org.elasticsearch.xpack.security.support.AbstractSecurityModule;

/**
 * Module used to bind various classes necessary for authorization
 */
public class AuthorizationModule extends AbstractSecurityModule.Node {

    public AuthorizationModule(Settings settings) {
        super(settings);
    }

    @Override
    protected void configureNode() {
        if (securityEnabled == false) {
            bind(RolesStore.class).toProvider(Providers.of(null));
            return;
        }

        // First the file and native roles stores must be bound...
        bind(ReservedRolesStore.class).asEagerSingleton();
        bind(FileRolesStore.class).asEagerSingleton();
        bind(NativeRolesStore.class).asEagerSingleton();
        // Then the composite roles store (which combines both) can be bound
        bind(RolesStore.class).to(CompositeRolesStore.class).asEagerSingleton();
        bind(AuthorizationService.class).asEagerSingleton();
    }

}
