/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel.agent.settings;

import org.elasticsearch.common.inject.AbstractModule;

public class MarvelSettingsModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(MarvelSettings.class).asEagerSingleton();
    }
}
