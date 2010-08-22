/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.routing;

import org.elasticsearch.common.collect.ImmutableList;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.inject.SpawnModules;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.routing.hash.HashFunction;
import org.elasticsearch.index.routing.hash.djb.DjbHashFunction;
import org.elasticsearch.index.routing.plain.PlainOperationRoutingModule;

import static org.elasticsearch.common.inject.Modules.*;

/**
 * @author kimchy (shay.banon)
 */
public class OperationRoutingModule extends AbstractModule implements SpawnModules {

    private final Settings indexSettings;

    public OperationRoutingModule(Settings indexSettings) {
        this.indexSettings = indexSettings;
    }

    @Override public Iterable<? extends Module> spawnModules() {
        return ImmutableList.of(createModule(indexSettings.getAsClass("index.routing.type", PlainOperationRoutingModule.class, "org.elasticsearch.index.routing.", "OperationRoutingModule"), indexSettings));
    }

    @Override protected void configure() {
        bind(HashFunction.class).to(indexSettings.getAsClass("index.routing.hash.type", DjbHashFunction.class, "org.elasticsearch.index.routing.hash.", "HashFunction")).asEagerSingleton();
    }
}
