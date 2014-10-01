/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.support.AbstractShieldModule;

/**
 *
 */
public class SecurityFilterModule extends AbstractShieldModule.Node {

    public SecurityFilterModule(Settings settings) {
        super(settings);
    }

    @Override
    protected void configureNode() {
        bind(SecurityFilter.class).asEagerSingleton();
    }
}
