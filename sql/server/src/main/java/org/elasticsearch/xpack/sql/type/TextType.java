/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.type;

import java.util.Map;
import java.util.Objects;

import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonMap;

public class TextType extends StringType {

    private static final TextType DEFAULT = new TextType(false, singletonMap("keyword", KeywordType.DEFAULT));

    private final boolean fieldData;

    TextType() {
        this(false, emptyMap());
    }

    TextType(boolean fieldData, Map<String, DataType> fields) {
        super(false, fields);
        this.fieldData = fieldData;
    }

    public boolean hasFieldData() {
        return fieldData;
    }

    @Override
    public boolean isInexact() {
        return true;
    }

    @Override
    public String esName() {
        return "text";
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), fieldData);
    }

    @Override
    public boolean equals(Object obj) {
        return super.equals(obj) && fieldData == ((TextType) obj).fieldData;
    }

    static DataType from(boolean fieldData, Map<String, DataType> fields) {
        return DEFAULT.fieldData == fieldData && DEFAULT.fields().equals(fields) ? DEFAULT : new TextType(fieldData, fields);
    }
}
