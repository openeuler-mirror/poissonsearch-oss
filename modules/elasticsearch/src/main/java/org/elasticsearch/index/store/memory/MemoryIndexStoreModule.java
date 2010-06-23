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

package org.elasticsearch.index.store.memory;

import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.store.IndexStore;

/**
 * @author kimchy (shay.banon)
 */
public class MemoryIndexStoreModule extends AbstractModule {

    private final Settings settings;

    public MemoryIndexStoreModule(Settings settings) {
        this.settings = settings;
    }

    @Override protected void configure() {
        String location = settings.get("index.store.memory.location", "direct");
        if ("direct".equalsIgnoreCase(location)) {
            bind(IndexStore.class).to(ByteBufferIndexStore.class).asEagerSingleton();
        } else if ("heap".equalsIgnoreCase(location)) {
            bind(IndexStore.class).to(HeapIndexStore.class).asEagerSingleton();
        } else {
            throw new ElasticSearchIllegalArgumentException("Memory location [" + location + "] is invalid, can be one of [direct,heap]");
        }
    }
}