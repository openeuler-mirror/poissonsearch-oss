/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.math;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.function.scalar.math.MathProcessor.MathOperation;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.tree.NodeInfo;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.type.DataTypeConversion;

/**
 * <a href="https://en.wikipedia.org/wiki/Floor_and_ceiling_functions">Floor</a>
 * function.
 */
public class Floor extends MathFunction {
    public Floor(Location location, Expression field) {
        super(location, field);
    }

    @Override
    protected NodeInfo<Floor> info() {
        return NodeInfo.create(this, Floor::new, field());
    }

    @Override
    protected Floor replaceChild(Expression newChild) {
        return new Floor(location(), newChild);
    }

    @Override
    protected MathOperation operation() {
        return MathOperation.FLOOR;
    }

    @Override
    public DataType dataType() {
        return DataTypeConversion.asInteger(field().dataType());
    }
}
