/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression;

import org.elasticsearch.xpack.sql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.type.DataTypes;

public class Exists extends SubQueryExpression {

    public Exists(Location location, LogicalPlan query) {
        this(location, query, null);
    }

    public Exists(Location location, LogicalPlan query, ExpressionId id) {
        super(location, query, id);
    }

    @Override
    protected SubQueryExpression clone(LogicalPlan newQuery) {
        return new Exists(location(), newQuery);
    }

    @Override
    public DataType dataType() {
        return DataTypes.BOOLEAN;
    }

    @Override
    public boolean nullable() {
        return false;
    }
}
