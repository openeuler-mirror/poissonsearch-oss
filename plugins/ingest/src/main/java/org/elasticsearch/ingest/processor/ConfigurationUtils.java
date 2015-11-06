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

package org.elasticsearch.ingest.processor;

import java.util.List;
import java.util.Map;

public final class ConfigurationUtils {

    private ConfigurationUtils() {
    }

    /**
     * Returns and removes the specified optional property from the specified configuration map.
     *
     * If the property value isn't of type string a {@link IllegalArgumentException} is thrown.
     */
    public static String readOptionalStringProperty(Map<String, Object> configuration, String propertyName) {
        Object value = configuration.remove(propertyName);
        return readString(propertyName, value);
    }

    /**
     * Returns and removes the specified property from the specified configuration map.
     *
     * If the property value isn't of type string an {@link IllegalArgumentException} is thrown.
     * If the property is missing an {@link IllegalArgumentException} is thrown
     */
    public static String readStringProperty(Map<String, Object> configuration, String propertyName) {
        return readStringProperty(configuration, propertyName, null);
    }

    /**
     * Returns and removes the specified property from the specified configuration map.
     *
     * If the property value isn't of type string a {@link IllegalArgumentException} is thrown.
     * If the property is missing and no default value has been specified a {@link IllegalArgumentException} is thrown
     */
    public static String readStringProperty(Map<String, Object> configuration, String propertyName, String defaultValue) {
        Object value = configuration.remove(propertyName);
        if (value == null && defaultValue != null) {
            return defaultValue;
        } else if (value == null) {
            throw new IllegalArgumentException("required property [" + propertyName + "] is missing");
        }
        return readString(propertyName, value);
    }

    private static String readString(String propertyName, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        throw new IllegalArgumentException("property [" + propertyName + "] isn't a string, but of type [" + value.getClass().getName() + "]");
    }

    /**
     * Returns and removes the specified property of type list from the specified configuration map.
     *
     * If the property value isn't of type list an {@link IllegalArgumentException} is thrown.
     * If the property is missing an {@link IllegalArgumentException} is thrown
     */
    public static List<String> readStringList(Map<String, Object> configuration, String propertyName) {
        Object value = configuration.remove(propertyName);
        if (value == null) {
            throw new IllegalArgumentException("required property [" + propertyName + "] is missing");
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> stringList = (List<String>) value;
            return stringList;
        } else {
            throw new IllegalArgumentException("property [" + propertyName + "] isn't a list, but of type [" + value.getClass().getName() + "]");
        }
    }
}
