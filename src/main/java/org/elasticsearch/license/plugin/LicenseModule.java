/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin;

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.license.plugin.core.LicensesService;

public class LicenseModule extends AbstractModule {
    @Override
    protected void configure() {
        //requestInjection(LicensesService.class);
        bind(LicensesService.class).asEagerSingleton();
    }
}
