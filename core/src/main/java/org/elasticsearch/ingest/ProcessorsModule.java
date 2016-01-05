/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.ingest;

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.multibindings.MapBinder;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry for processor factories
 * @see org.elasticsearch.ingest.Processor.Factory
 * @see ProcessorFactoryProvider
 */
public class ProcessorsModule extends AbstractModule {

    private final Map<String, ProcessorFactoryProvider> processorFactoryProviders = new HashMap<>();

    @Override
    protected void configure() {
        MapBinder<String, ProcessorFactoryProvider> mapBinder = MapBinder.newMapBinder(binder(), String.class, ProcessorFactoryProvider.class);
        for (Map.Entry<String, ProcessorFactoryProvider> entry : processorFactoryProviders.entrySet()) {
            mapBinder.addBinding(entry.getKey()).toInstance(entry.getValue());
        }
    }

    /**
     * Adds a processor factory under a specific type name.
     */
    public void addProcessor(String type, ProcessorFactoryProvider processorFactoryProvider) {
        processorFactoryProviders.put(type, processorFactoryProvider);
    }
}
