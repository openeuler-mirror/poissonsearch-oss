/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.predicate;

import org.elasticsearch.xpack.sql.expression.BinaryOperator.Negateable;
import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.tree.Location;

public class LessThanOrEqual extends BinaryComparison implements Negateable {

    public LessThanOrEqual(Location location, Expression left, Expression right) {
        super(location, left, right);
    }

    @Override
    public Object fold() {
        Integer compare = compare(left().fold(), right().fold());
        return compare != null && compare.intValue() <= 0;
    }

    @Override
    public GreaterThanOrEqual swapLeftAndRight() {
        return new GreaterThanOrEqual(location(), right(), left());
    }

    @Override
    public GreaterThan negate() {
        return new GreaterThan(location(), left(), right());
    }

    @Override
    public String symbol() {
        return "<=";
    }
}
