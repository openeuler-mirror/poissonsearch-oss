/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.support.http;

import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.SpawnModules;
import org.elasticsearch.watcher.support.http.auth.AuthModule;


/**
 */
public class HttpClientModule extends AbstractModule implements SpawnModules {

    @Override
    public Iterable<? extends Module> spawnModules() {
        return ImmutableList.of(new AuthModule());
    }

    @Override
    protected void configure() {
        bind(TemplatedHttpRequest.Parser.class).asEagerSingleton();
        bind(HttpRequest.Parser.class).asEagerSingleton();
        bind(HttpClient.class).asEagerSingleton();
    }

}
