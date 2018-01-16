/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.predicate;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.tree.Location;

import java.util.Objects;

public class Equals extends BinaryComparison {

    public Equals(Location location, Expression left, Expression right) {
        super(location, left, right);
    }

    @Override
    public Object fold() {
        return Objects.equals(left().fold(), right().fold());
    }

    @Override
    public Equals swapLeftAndRight() {
        return new Equals(location(), right(), left());
    }

    @Override
    public String symbol() {
        return "=";
    }
}
