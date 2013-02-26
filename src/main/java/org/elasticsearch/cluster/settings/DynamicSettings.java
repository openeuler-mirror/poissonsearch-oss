/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.cluster.settings;

import com.google.common.collect.ImmutableSet;
import org.elasticsearch.common.regex.Regex;

import java.util.Arrays;
import java.util.HashSet;

/**
 */
public class DynamicSettings {

    private ImmutableSet<String> dynamicSettings = ImmutableSet.of();

    public boolean hasDynamicSetting(String key) {
        for (String dynamicSetting : dynamicSettings) {
            if (Regex.simpleMatch(dynamicSetting, key)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void addDynamicSettings(String... settings) {
        HashSet<String> updatedSettings = new HashSet<String>(dynamicSettings);
        updatedSettings.addAll(Arrays.asList(settings));
        dynamicSettings = ImmutableSet.copyOf(updatedSettings);
    }

}
