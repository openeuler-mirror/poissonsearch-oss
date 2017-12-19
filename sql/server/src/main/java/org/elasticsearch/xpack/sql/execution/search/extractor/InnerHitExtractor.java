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

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public class InnerHitExtractor implements HitExtractor {
    /**
     * Stands for {@code inner}. We try to use short names for {@link HitExtractor}s
     * to save a few bytes when when we send them back to the user.
     */
    static final String NAME = "i";
    private final String hitName, fieldName;
    private final boolean useDocValue;
    private final String[] tree;

    public InnerHitExtractor(String hitName, String name, boolean useDocValue) {
        this.hitName = hitName;
        this.fieldName = name;
        this.useDocValue = useDocValue;
        this.tree = useDocValue ? Strings.EMPTY_ARRAY : Strings.tokenizeToStringArray(name, ".");
    }

    InnerHitExtractor(StreamInput in) throws IOException {
        hitName = in.readString();
        fieldName = in.readString();
        useDocValue = in.readBoolean();
        tree = useDocValue ? Strings.EMPTY_ARRAY : Strings.tokenizeToStringArray(fieldName, ".");
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(hitName);
        out.writeString(fieldName);
        out.writeBoolean(useDocValue);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object get(SearchHit hit) {
        if (useDocValue) {
            DocumentField field = hit.field(fieldName);
            return field != null ? field.getValue() : null;
        }
        else {
            Map<String, Object> source = hit.getSourceAsMap();
            if (source == null) {
                return null;
            }
            Object value = null;
            for (String node : tree) {
                if (value != null) {
                    if (value instanceof Map) {
                        source = (Map<String, Object>) value;
                    }
                    else {
                        throw new ExecutionException("Cannot extract value %s from source", fieldName);
                    }
                }
                value = source.get(node);
            }
            return value;
        }
    }

    @Override
    public String innerHitName() {
        return hitName;
    }

    String fieldName() {
        return fieldName;
    }

    public String hitName() {
        return hitName;
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
        InnerHitExtractor other = (InnerHitExtractor) obj;
        return fieldName.equals(other.fieldName)
                && hitName.equals(other.hitName)
                && useDocValue == other.useDocValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(hitName, fieldName, useDocValue);
    }
}
