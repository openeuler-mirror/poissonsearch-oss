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

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;

/**
 * Represents a single document being captured before indexing and holds the source and metadata (like id, type and index).
 */
public final class IngestDocument {

    public final static String INGEST_KEY = "_ingest";
    public final static String SOURCE_KEY = "_source";

    static final String TIMESTAMP = "timestamp";

    private final Map<String, Object> sourceAndMetadata;
    private final Map<String, String> ingestMetadata;

    public IngestDocument(String index, String type, String id, String routing, String parent, String timestamp, String ttl, Map<String, Object> source) {
        this.sourceAndMetadata = new HashMap<>();
        this.sourceAndMetadata.putAll(source);
        this.sourceAndMetadata.put(MetaData.INDEX.getFieldName(), index);
        this.sourceAndMetadata.put(MetaData.TYPE.getFieldName(), type);
        this.sourceAndMetadata.put(MetaData.ID.getFieldName(), id);
        if (routing != null) {
            this.sourceAndMetadata.put(MetaData.ROUTING.getFieldName(), routing);
        }
        if (parent != null) {
            this.sourceAndMetadata.put(MetaData.PARENT.getFieldName(), parent);
        }
        if (timestamp != null) {
            this.sourceAndMetadata.put(MetaData.TIMESTAMP.getFieldName(), timestamp);
        }
        if (ttl != null) {
            this.sourceAndMetadata.put(MetaData.TTL.getFieldName(), ttl);
        }

        this.ingestMetadata = new HashMap<>();
        DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZZ", Locale.ROOT);
        df.setTimeZone(TimeZone.getTimeZone("UTC"));
        this.ingestMetadata.put(TIMESTAMP, df.format(new Date()));
    }

    /**
     * Copy constructor that creates a new {@link IngestDocument} which has exactly the same properties as the one provided as argument
     */
    public IngestDocument(IngestDocument other) {
        this(new HashMap<>(other.sourceAndMetadata), new HashMap<>(other.ingestMetadata));
    }

    /**
     * Constructor needed for testing that allows to create a new {@link IngestDocument} given the provided elasticsearch metadata,
     * source and ingest metadata. This is needed because the ingest metadata will be initialized with the current timestamp at
     * init time, which makes equality comparisons impossible in tests.
     */
    public IngestDocument(Map<String, Object> sourceAndMetadata, Map<String, String> ingestMetadata) {
        this.sourceAndMetadata = sourceAndMetadata;
        this.ingestMetadata = ingestMetadata;
    }

    /**
     * Returns the value contained in the document for the provided path
     * @param path The path within the document in dot-notation
     * @param clazz The expected class of the field value
     * @return the value for the provided path if existing, null otherwise
     * @throws IllegalArgumentException if the path is null, empty, invalid, if the field doesn't exist
     * or if the field that is found at the provided path is not of the expected type.
     */
    public <T> T getFieldValue(String path, Class<T> clazz) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (String pathElement : fieldPath.pathElements) {
            context = resolve(pathElement, path, context);
        }
        return cast(path, context, clazz);
    }

    /**
     * Checks whether the document contains a value for the provided path
     * @param path The path within the document in dot-notation
     * @return true if the document contains a value for the field, false otherwise
     * @throws IllegalArgumentException if the path is null, empty or invalid.
     */
    public boolean hasField(String path) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (int i = 0; i < fieldPath.pathElements.length - 1; i++) {
            String pathElement = fieldPath.pathElements[i];
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

        String leafKey = fieldPath.pathElements[fieldPath.pathElements.length - 1];
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
     * Removes the field identified by the provided path.
     * @param fieldPathTemplate Resolves to the path with dot-notation within the document
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the field doesn't exist.
     */
    public void removeField(TemplateService.Template fieldPathTemplate) {
        Map<String, Object> model = createTemplateModel();
        removeField(fieldPathTemplate.execute(model));
    }

    /**
     * Removes the field identified by the provided path.
     * @param path the path of the field to be removed
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the field doesn't exist.
     */
    public void removeField(String path) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (int i = 0; i < fieldPath.pathElements.length - 1; i++) {
            context = resolve(fieldPath.pathElements[i], path, context);
        }

        String leafKey = fieldPath.pathElements[fieldPath.pathElements.length - 1];
        if (context instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) context;
            if (map.containsKey(leafKey)) {
                map.remove(leafKey);
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
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the field doesn't exist.
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
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the field doesn't exist.
     */
    public void setFieldValue(String path, Object value) {
        setFieldValue(path, value, false);
    }

    /**
     * Sets the provided value to the provided path in the document.
     * Any non existing path element will be created. If the last element is a list,
     * the value will replace the existing list.
     * @param fieldPathTemplate Resolves to the path with dot-notation within the document
     * @param valueSource The value source that will produce the value to put in for the path key
     * @throws IllegalArgumentException if the path is null, empty, invalid or if the field doesn't exist.
     */
    public void setFieldValue(TemplateService.Template fieldPathTemplate, ValueSource valueSource) {
        Map<String, Object> model = createTemplateModel();
        setFieldValue(fieldPathTemplate.execute(model), valueSource.copyAndResolve(model), false);
    }

    private void setFieldValue(String path, Object value, boolean append) {
        FieldPath fieldPath = new FieldPath(path);
        Object context = fieldPath.initialContext;
        for (int i = 0; i < fieldPath.pathElements.length - 1; i++) {
            String pathElement = fieldPath.pathElements[i];
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

        String leafKey = fieldPath.pathElements[fieldPath.pathElements.length - 1];
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
                        return;
                    }
                }
            }
            map.put(leafKey, value);
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
            list.set(index, value);
        } else {
            throw new IllegalArgumentException("cannot set [" + leafKey + "] with parent object of type [" + context.getClass().getName() + "] as part of path [" + path + "]");
        }
    }

    private static <T> T cast(String path, Object object, Class<T> clazz) {
        if (object == null) {
            return null;
        }
        if (clazz.isInstance(object)) {
            return clazz.cast(object);
        }
        throw new IllegalArgumentException("field [" + path + "] of type [" + object.getClass().getName() + "] cannot be cast to [" + clazz.getName() + "]");
    }

    private Map<String, Object> createTemplateModel() {
        Map<String, Object> model = new HashMap<>(sourceAndMetadata);
        model.put(SOURCE_KEY, sourceAndMetadata);
        // If there is a field in the source with the name '_ingest' it gets overwritten here,
        // if access to that field is required then it get accessed via '_source._ingest'
        model.put(INGEST_KEY, ingestMetadata);
        return model;
    }

    /**
     * one time operation that extracts the metadata fields from the ingest document and returns them.
     * Metadata fields that used to be accessible as ordinary top level fields will be removed as part of this call.
     */
    public Map<MetaData, String> extractMetadata() {
        Map<MetaData, String> metadataMap = new HashMap<>();
        for (MetaData metaData : MetaData.values()) {
            metadataMap.put(metaData, cast(metaData.getFieldName(), sourceAndMetadata.remove(metaData.getFieldName()), String.class));
        }
        return metadataMap;
    }

    /**
     * Returns the available ingest metadata fields, by default only timestamp, but it is possible to set additional ones.
     * Use only for reading values, modify them instead using {@link #setFieldValue(String, Object)} and {@link #removeField(String)}
     */
    public Map<String, String> getIngestMetadata() {
        return this.ingestMetadata;
    }

    /**
     * Returns the document including its metadata fields, unless {@link #extractMetadata()} has been called, in which case the
     * metadata fields will not be present anymore. Should be used only for reading.
     * Modify the document instead using {@link #setFieldValue(String, Object)} and {@link #removeField(String)}
     */
    public Map<String, Object> getSourceAndMetadata() {
        return this.sourceAndMetadata;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) { return true; }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        IngestDocument other = (IngestDocument) obj;
        return Objects.equals(sourceAndMetadata, other.sourceAndMetadata) &&
                Objects.equals(ingestMetadata, other.ingestMetadata);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceAndMetadata, ingestMetadata);
    }

    @Override
    public String toString() {
        return "IngestDocument{" +
                " sourceAndMetadata=" + sourceAndMetadata +
                ", ingestMetadata=" + ingestMetadata +
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

    }

    private class FieldPath {
        private final String[] pathElements;
        private final Object initialContext;

        private FieldPath(String path) {
            if (Strings.isEmpty(path)) {
                throw new IllegalArgumentException("path cannot be null nor empty");
            }
            String newPath;
            if (path.startsWith(INGEST_KEY + ".")) {
                initialContext = ingestMetadata;
                newPath = path.substring(8, path.length());
            } else {
                initialContext = sourceAndMetadata;
                if (path.startsWith(SOURCE_KEY + ".")) {
                    newPath = path.substring(8, path.length());
                } else {
                    newPath = path;
                }
            }
            this.pathElements = Strings.splitStringToArray(newPath, '.');
            if (pathElements.length == 0) {
                throw new IllegalArgumentException("path [" + path + "] is not valid");
            }
        }
    }
}
