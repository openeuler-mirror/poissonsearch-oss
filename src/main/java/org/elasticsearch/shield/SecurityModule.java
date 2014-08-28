/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield;

import org.elasticsearch.action.ActionModule;
import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.PreProcessModule;
import org.elasticsearch.common.inject.SpawnModules;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.audit.AuditTrailModule;
import org.elasticsearch.shield.authc.AuthenticationModule;
import org.elasticsearch.shield.authz.AuthorizationModule;
import org.elasticsearch.shield.n2n.N2NModule;
import org.elasticsearch.shield.transport.SecuredTransportModule;
import org.elasticsearch.shield.transport.netty.NettySecuredHttpServerTransportModule;
import org.elasticsearch.shield.transport.netty.NettySecuredTransportModule;

/**
 *
 */
public class SecurityModule extends AbstractModule implements SpawnModules, PreProcessModule {

    private final Settings settings;
    private final boolean isClient;
    private final boolean isShieldEnabled;

    public SecurityModule(Settings settings) {
        this.settings = settings;
        this.isClient = settings.getAsBoolean("node.client", false);
        this.isShieldEnabled = settings.getComponentSettings(SecurityModule.class).getAsBoolean("enabled", true);
    }

    @Override
    public void processModule(Module module) {
        if (module instanceof ActionModule && isShieldEnabled && !isClient) {
            ((ActionModule) module).registerFilter(SecurityFilter.Action.class);
        }
    }

    @Override
    public Iterable<? extends Module> spawnModules() {
        // don't spawn modules if shield is explicitly disabled
        if (!isShieldEnabled) {
            return ImmutableList.of();
        }

        // spawn needed parts in client mode
        if (isClient) {
            return ImmutableList.of(
                    new N2NModule(),
                    new SecuredTransportModule()
            );
        }

        return ImmutableList.of(
                new AuthenticationModule(settings),
                new AuthorizationModule(),
                new AuditTrailModule(settings),
                new N2NModule(),
                new NettySecuredHttpServerTransportModule(),
                new NettySecuredTransportModule(),
                new SecuredTransportModule());
    }

    @Override
    protected void configure() {
    }
}
