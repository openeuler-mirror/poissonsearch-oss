/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.execution.search.extractor;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.document.DocumentField;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.xpack.sql.execution.ExecutionException;
import org.joda.time.ReadableInstant;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Extractor for ES fields. Works for both 'normal' fields but also nested ones (which require hitName to be set).
 * The latter is used as metadata in assembling the results in the tabular response.
 */
public class FieldHitExtractor implements HitExtractor {

    private static final boolean ARRAYS_LENIENCY = false;

    /**
     * Stands for {@code field}. We try to use short names for {@link HitExtractor}s
     * to save a few bytes when when we send them back to the user.
     */
    static final String NAME = "f";

    private final String fieldName, hitName;
    private final boolean useDocValue;
    private final String[] path;

    public FieldHitExtractor(String name, boolean useDocValue) {
        this(name, useDocValue, null);
    }

    public FieldHitExtractor(String name, boolean useDocValue, String hitName) {
        this.fieldName = name;
        this.useDocValue = useDocValue;
        this.hitName = hitName;
        this.path = useDocValue ? Strings.EMPTY_ARRAY : Strings.tokenizeToStringArray(fieldName, ".");
    }

    FieldHitExtractor(StreamInput in) throws IOException {
        fieldName = in.readString();
        useDocValue = in.readBoolean();
        hitName = in.readOptionalString();
        path = useDocValue ? Strings.EMPTY_ARRAY : Strings.tokenizeToStringArray(fieldName, ".");
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(fieldName);
        out.writeBoolean(useDocValue);
        out.writeOptionalString(hitName);
    }

    @Override
    public Object get(SearchHit hit) {
        Object value = null;
        if (useDocValue) {
            DocumentField field = hit.field(fieldName);
            if (field != null) {
                value = unwrapMultiValue(field.getValues());
            }
        } else {
            Map<String, Object> source = hit.getSourceAsMap();
            if (source != null) {
                value = extractFromSource(source);
            }
        }
        return value;
    }

    private Object unwrapMultiValue(Object values) {
        if (values == null) {
            return null;
        }
        if (values instanceof List) {
            List<?> list = (List<?>) values;
            if (list.isEmpty()) {
                return null;
            } else {
                if (ARRAYS_LENIENCY || list.size() == 1) {
                    return unwrapMultiValue(list.get(0));
                } else {
                    throw new ExecutionException("Arrays (returned by [%s]) are not supported", fieldName);
                }
            }
        }
        if (values instanceof Map) {
            throw new ExecutionException("Objects (returned by [%s]) are not supported", fieldName);
        }
        if (values instanceof Long || values instanceof Double || values instanceof String || values instanceof Boolean) {
            return values;
        }
        if (values instanceof ReadableInstant) {
            return ((ReadableInstant) values).getMillis();
        }
        throw new ExecutionException("Type %s (returned by [%s]) is not supported", values.getClass().getSimpleName(), fieldName);
    }

    @SuppressWarnings("unchecked")
    Object extractFromSource(Map<String, Object> map) {
        Object value = map;
        boolean first = true;
        // each node is a key inside the map
        for (String node : path) {
            if (value == null) {
                return null;
            } else if (first || value instanceof Map) {
                first = false;
                value = ((Map<String, Object>) value).get(node);
            } else {
                throw new ExecutionException("Cannot extract value [%s] from source", fieldName);
            }
        }
        return unwrapMultiValue(value);
    }

    @Override
    public String hitName() {
        return hitName;
    }

    public String fieldName() {
        return fieldName;
    }

    @Override
    public String toString() {
        return fieldName + "@" + hitName;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        FieldHitExtractor other = (FieldHitExtractor) obj;
        return fieldName.equals(other.fieldName)
                && hitName.equals(other.hitName)
                && useDocValue == other.useDocValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, useDocValue, hitName);
    }
}