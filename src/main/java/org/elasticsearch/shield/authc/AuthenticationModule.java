/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc;

import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.SpawnModules;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.authc.esusers.ESUsersModule;
import org.elasticsearch.shield.authc.ldap.LdapModule;
import org.elasticsearch.shield.authc.system.SystemRealm;

/**
 *
 */
public class AuthenticationModule extends AbstractModule implements SpawnModules {

    private final Settings settings;

    public AuthenticationModule(Settings settings) {
        this.settings = settings;
    }

    @Override
    public Iterable<? extends Module> spawnModules() {
        ImmutableList.Builder<Module> modules = ImmutableList.builder();
        modules.add(new SystemRealm.Module());
        if (ESUsersModule.enabled(settings)) {
            modules.add(new ESUsersModule());
        }
        if (LdapModule.enabled(settings)) {
            modules.add(new LdapModule(settings));
        }
        return modules.build();
    }

    @Override
    protected void configure() {
        bind(AuthenticationService.class).to(InternalAuthenticationService.class);
    }
}
