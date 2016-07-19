/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.security.support.AbstractSecurityModule;

/**
 *
 */
public class SecurityModule extends AbstractSecurityModule {

    public SecurityModule(Settings settings) {
        super(settings);
    }

    @Override
    protected void configure(boolean clientMode) {
        if (clientMode) {
            return;
        }

        XPackPlugin.bindFeatureSet(binder(), SecurityFeatureSet.class);

        if (securityEnabled) {
            bind(SecurityLifecycleService.class).asEagerSingleton();
        }
    }

}
