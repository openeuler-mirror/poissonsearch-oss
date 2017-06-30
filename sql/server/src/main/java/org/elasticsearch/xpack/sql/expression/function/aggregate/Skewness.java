/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.aggregate;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.tree.Location;

public class Skewness extends NumericAggregate implements MatrixStatsEnclosed {

    public Skewness(Location location, Expression argument) {
        super(location, argument);
    }

    @Override
    public String innerName() {
        return "skewness";
    }
}
