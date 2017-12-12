/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression;

import java.util.Objects;

import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.type.DataType;

public abstract class TypedAttribute extends Attribute {

    private final DataType dataType;

    protected TypedAttribute(Location location, String name, DataType dataType) {
        this(location, name, dataType, null, true, null, false);
    }

    protected TypedAttribute(Location location, String name, DataType dataType, String qualifier, boolean nullable, ExpressionId id, boolean synthetic) {
        super(location, name, qualifier, nullable, id, synthetic);
        this.dataType = dataType;
    }

    @Override
    public DataType dataType() {
        return dataType;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        
        TypedAttribute other = (TypedAttribute) obj;
        return Objects.equals(name(), other.name())
                && Objects.equals(id(), other.id())
                && Objects.equals(nullable(), other.nullable())
                && Objects.equals(dataType(), other.dataType())
                && Objects.equals(qualifier(), other.qualifier())
                && Objects.equals(synthetic(), other.synthetic());
    }
}
