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

package org.elasticsearch.index.engine;

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.engine.robin.RobinEngineModule;
import org.elasticsearch.index.engine.robin.RobinIndexEngineModule;

import static org.elasticsearch.common.inject.ModulesFactory.*;

/**
 * @author kimchy (shay.banon)
 */
public class IndexEngineModule extends AbstractModule {

    public static final class EngineSettings {
        public static final String ENGINE_TYPE = "index.engine.type";
        public static final Class<? extends Module> DEFAULT_INDEX_ENGINE = RobinIndexEngineModule.class;
        public static final Class<? extends Module> DEFAULT_ENGINE = RobinEngineModule.class;
    }

    private final Settings settings;

    public IndexEngineModule(Settings settings) {
        this.settings = settings;
    }

    @Override protected void configure() {
        createModule(settings.getAsClass(EngineSettings.ENGINE_TYPE, EngineSettings.DEFAULT_INDEX_ENGINE, "org.elasticsearch.index.engine.", "IndexEngineModule"), settings).configure(binder());
    }
}