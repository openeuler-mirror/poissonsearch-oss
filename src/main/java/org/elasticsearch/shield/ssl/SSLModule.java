/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.ssl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.support.AbstractShieldModule;

/**
 *
 */
public class SSLModule extends AbstractShieldModule {

    public SSLModule(Settings settings) {
        super(settings);
    }

    @Override
    protected void configure(boolean clientMode) {
        bind(SSLServiceProvider.class).asEagerSingleton();
    }
}
