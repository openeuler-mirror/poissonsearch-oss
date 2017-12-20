/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.arithmetic;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.function.scalar.arithmetic.BinaryArithmeticProcessor.BinaryArithmeticOperation;
import org.elasticsearch.xpack.sql.tree.Location;

/**
 * Subtraction function ({@code a - b}).
 */
public class Sub extends ArithmeticFunction {

    public Sub(Location location, Expression left, Expression right) {
        super(location, left, right, BinaryArithmeticOperation.SUB);
    }

    @Override
    public Object fold() {
        return Arithmetics.sub((Number) left().fold(), (Number) right().fold());
    }
}
