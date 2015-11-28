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

import org.elasticsearch.common.Strings;

import java.util.*;

/**
 * Represents a single document being captured before indexing and holds the source and meta data (like id, type and index).
 */
public final class IngestDocument {

    private final Map<String, String> metaData;
    private final Map<String, Object> source;

    private boolean sourceModified = false;

    public IngestDocument(String index, String type, String id, Map<String, Object> source) {
        this(index, type, id, null, null, null, null, source);
    }

    public IngestDocument(String index, String type, String id, String routing, String parent, String timestamp, String ttl, Map<String, Object> source) {
        this.metaData = new HashMap<>();
        this.metaData.put(MetaData.INDEX.getFieldName(), index);
        this.metaData.put(MetaData.TYPE.getFieldName(), type);
        this.metaData.put(MetaData.ID.getFieldName(), id);
        if (routing != null) {
            this.metaData.put(MetaData.ROUTING.getFieldName(), routing);
        }
        if (parent != null) {
            this.metaData.put(MetaData.PARENT.getFieldName(), parent);
        }
        if (timestamp != null) {
            this.metaData.put(MetaData.TIMESTAMP.getFieldName(), timestamp);
        }
        if (ttl != null) {
            this.metaData.put(MetaData.TTL.getFieldName(), ttl);
        }
        this.source = source;
    }

    public IngestDocument(IngestDocument other) {
        this.metaData = new HashMap<>(other.metaData);
        this.source = new HashMap<>(other.source);
    }

    /**
     * Returns the value contained in the document for the provided path
     * @param path The path within the document in dot-notation
     * @param clazz The expected class of the field value
     * @return the value for the provided path if existing, null otherwise
     * @throws IllegalArgumentException if the field is null, empty, or if the source contains a field within the path
     * which is not of the expected type
     */
    public <T> T getFieldValue(String path, Class<T> clazz) {
        if (Strings.isEmpty(path)) {
            throw new IllegalArgumentException("path cannot be null nor empty");
        }
        String[] pathElements = Strings.splitStringToArray(path, '.');
        assert pathElements.length > 0;

        Object context = source;
        for (String pathElement : pathElements) {
            context = resolve(pathElement, path, context);
        }

        if (context == null) {
            return null;
        }
        if (clazz.isInstance(context)) {
            return clazz.cast(context);
        }
        throw new IllegalArgumentException("field [" + path + "] of type [" + context.getClass().getName() + "] cannot be cast to [" + clazz.getName() + "]");
    }

    /**
     * Checks whether the document contains a value for the provided path
     * @param path The path within the document in dot-notation
     * @return true if the document contains a value for the field, false otherwise
     */
    public boolean hasField(String path) {
        if (Strings.isEmpty(path)) {
            return false;
        }
        String[] pathElements = Strings.splitStringToArray(path, '.');
        assert pathElements.length > 0;

        Object context = source;
        for (int i = 0; i < pathElements.length - 1; i++) {
            String pathElement = pathElements[i];
            if (context == null) {
                return false;
            }
            if (context instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) context;
                context = map.get(pathElement);
            } else if (context instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) context;
                try {
                    int index = Integer.parseInt(pathElement);
                    if (index < 0 || index >= list.size()) {
                        return false;
                    }
                    context = list.get(index);
                } catch (NumberFormatException e) {
                    return false;
                }

            } else {
                return false;
            }
        }

        String leafKey = pathElements[pathElements.length - 1];
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            return map.containsKey(leafKey);
        }
        if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            try {
                int index = Integer.parseInt(leafKey);
                return index >= 0 && index < list.size();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Removes the field identified by the provided path
     * @param path the path of the field to be removed
     */
    public void removeField(String path) {
        if (Strings.isEmpty(path)) {
            throw new IllegalArgumentException("path cannot be null nor empty");
        }
        String[] pathElements = Strings.splitStringToArray(path, '.');
        assert pathElements.length > 0;

        Object context = source;
        for (int i = 0; i < pathElements.length - 1; i++) {
            context = resolve(pathElements[i], path, context);
        }

        String leafKey = pathElements[pathElements.length - 1];
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            if (map.containsKey(leafKey)) {
                map.remove(leafKey);
                this.sourceModified = true;
                return;
            }
            throw new IllegalArgumentException("field [" + leafKey + "] not present as part of path [" + path + "]");
        }
        if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            int index;
            try {
                index = Integer.parseInt(leafKey);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[" + leafKey + "] is not an integer, cannot be used as an index as part of path [" + path + "]", e);
            }
            if (index < 0 || index >= list.size()) {
                throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" + list.size() + "] as part of path [" + path + "]");
            }
            list.remove(index);
            this.sourceModified = true;
            return;
        }

        if (context == null) {
            throw new IllegalArgumentException("cannot remove [" + leafKey + "] from null as part of path [" + path + "]");
        }
        throw new IllegalArgumentException("cannot remove [" + leafKey + "] from object of type [" + context.getClass().getName() + "] as part of path [" + path + "]");
    }

    private static Object resolve(String pathElement, String fullPath, Object context) {
        if (context == null) {
            throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from null as part of path [" + fullPath + "]");
        }
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            if (map.containsKey(pathElement)) {
                return map.get(pathElement);
            }
            throw new IllegalArgumentException("field [" + pathElement + "] not present as part of path [" + fullPath + "]");
        }
        if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            int index;
            try {
                index = Integer.parseInt(pathElement);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[" + pathElement + "] is not an integer, cannot be used as an index as part of path [" + fullPath + "]", e);
            }
            if (index < 0 || index >= list.size()) {
                throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" + list.size() + "] as part of path [" + fullPath + "]");
            }
            return list.get(index);
        }
        throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from object of type [" + context.getClass().getName() + "] as part of path [" + fullPath + "]");
    }

    /**
     * Appends the provided value to the provided path in the document.
     * Any non existing path element will be created. Same as {@link #setFieldValue(String, Object)}
     * but if the last element is a list, the value will be appended to the existing list.
     * @param path The path within the document in dot-notation
     * @param value The value to put in for the path key
     */
    public void appendFieldValue(String path, Object value) {
        setFieldValue(path, value, true);
    }

    /**
     * Sets the provided value to the provided path in the document.
     * Any non existing path element will be created. If the last element is a list,
     * the value will replace the existing list.
     * @param path The path within the document in dot-notation
     * @param value The value to put in for the path key
     */
    public void setFieldValue(String path, Object value) {
        setFieldValue(path, value, false);
    }

    private void setFieldValue(String path, Object value, boolean append) {
        if (Strings.isEmpty(path)) {
            throw new IllegalArgumentException("path cannot be null nor empty");
        }
        String[] pathElements = Strings.splitStringToArray(path, '.');
        assert pathElements.length > 0;

        Object context = source;
        for (int i = 0; i < pathElements.length - 1; i++) {
            String pathElement = pathElements[i];
            if (context == null) {
                throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from null as part of path [" + path + "]");
            }
            if (context instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) context;
                if (map.containsKey(pathElement)) {
                    context = map.get(pathElement);
                } else {
                    HashMap<Object, Object> newMap = new HashMap<>();
                    map.put(pathElement, newMap);
                    sourceModified = true;
                    context = newMap;
                }
            } else if (context instanceof List) {
                @SuppressWarnings("unchecked")
                List<Object> list = (List<Object>) context;
                int index;
                try {
                    index = Integer.parseInt(pathElement);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("[" + pathElement + "] is not an integer, cannot be used as an index as part of path [" + path + "]", e);
                }
                if (index < 0 || index >= list.size()) {
                    throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" + list.size() + "] as part of path [" + path + "]");
                }
                context = list.get(index);
            } else {
                throw new IllegalArgumentException("cannot resolve [" + pathElement + "] from object of type [" + context.getClass().getName() + "] as part of path [" + path + "]");
            }
        }

        String leafKey = pathElements[pathElements.length - 1];
        if (context == null) {
            throw new IllegalArgumentException("cannot set [" + leafKey + "] with null parent as part of path [" + path + "]");
        }
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            if (append) {
                if (map.containsKey(leafKey)) {
                    Object object = map.get(leafKey);
                    if (object instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) object;
                        list.add(value);
                        sourceModified = true;
                        return;
                    }
                }
            }
            map.put(leafKey, value);
            sourceModified = true;
        } else if (context instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) context;
            int index;
            try {
                index = Integer.parseInt(leafKey);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("[" + leafKey + "] is not an integer, cannot be used as an index as part of path [" + path + "]", e);
            }
            if (index < 0 || index >= list.size()) {
                throw new IllegalArgumentException("[" + index + "] is out of bounds for array with length [" + list.size() + "] as part of path [" + path + "]");
            }
            list.add(index, value);
            this.sourceModified = true;
        } else {
            throw new IllegalArgumentException("cannot set [" + leafKey + "] with parent object of type [" + context.getClass().getName() + "] as part of path [" + path + "]");
        }
    }

    public String getMetadata(MetaData metaData) {
        return this.metaData.get(metaData.getFieldName());
    }

    public void setMetaData(MetaData metaData, String value) {
        this.metaData.put(metaData.getFieldName(), value);
    }

    /**
     * Returns the document. Should be used only for reading. Any change made to the returned map will
     * not be reflected to the sourceModified flag. Modify the document instead using {@link #setFieldValue(String, Object)}
     * and {@link #removeField(String)}
     */
    public Map<String, Object> getSource() {
        return source;
    }

    public boolean isSourceModified() {
        return sourceModified;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) { return true; }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        IngestDocument other = (IngestDocument) obj;
        return Objects.equals(source, other.source) &&
                Objects.equals(metaData, other.metaData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(metaData, source);
    }

    @Override
    public String toString() {
        return "IngestDocument{" +
                "metaData=" + metaData +
                ", source=" + source +
                '}';
    }

    public enum MetaData {

        INDEX("_index"),
        TYPE("_type"),
        ID("_id"),
        ROUTING("_routing"),
        PARENT("_parent"),
        TIMESTAMP("_timestamp"),
        TTL("_ttl");

        private final String fieldName;

        MetaData(String fieldName) {
            this.fieldName = fieldName;
        }

        public String getFieldName() {
            return fieldName;
        }

        public static MetaData fromString(String value) {
            switch (value) {
                case "_index":
                    return INDEX;
                case "_type":
                    return TYPE;
                case "_id":
                    return ID;
                case "_routing":
                    return ROUTING;
                case "_parent":
                    return PARENT;
                case "_timestamp":
                    return TIMESTAMP;
                case "_ttl":
                    return TTL;
                default:
                    throw new IllegalArgumentException("no valid metadata field name [" + value + "]");
            }
        }

    }

}
