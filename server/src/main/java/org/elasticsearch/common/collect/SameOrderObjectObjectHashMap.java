/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.common.collect;

import com.carrotsearch.hppc.ObjectObjectAssociativeContainer;
import com.carrotsearch.hppc.ObjectObjectHashMap;

/**
 * ObjectObjectHashMap without seed. In order to get the same order for each iteration.
 *
 * @since 2023-06-13
 */
public class SameOrderObjectObjectHashMap<KType, VType> extends ObjectObjectHashMap<KType, VType> {
    /**
     * New instance with sane defaults.
     */
    public SameOrderObjectObjectHashMap() {
        super();
    }

    /**
     * New instance with sane defaults.
     *
     * @param expectedElements The expected number of elements guaranteed not to cause buffer expansion (inclusive).
     */
    public SameOrderObjectObjectHashMap(int expectedElements) {
        super(expectedElements);
    }

    /**
     * New instance with the provided defaults.
     *
     * @param expectedElements The expected number of elements guaranteed not to cause a rehash (inclusive).
     * @param loadFactor The load factor for internal buffers. Insane load factors (zero, full capacity)
     *                   are rejected by {@link #verifyLoadFactor(double)}.
     */
    public SameOrderObjectObjectHashMap(int expectedElements, double loadFactor) {
        super(expectedElements, loadFactor);
    }

    /**
     * Create a hash map from all key-value pairs of another container.
     *
     * @param container original container
     */
    public SameOrderObjectObjectHashMap(ObjectObjectAssociativeContainer<? extends KType, ? extends VType> container) {
        super(container);
    }

    @Override
    protected int nextIterationSeed() {
        return 0;
    }
}
