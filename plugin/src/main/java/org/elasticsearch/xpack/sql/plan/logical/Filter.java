/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plan.logical;

import java.util.Objects;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.tree.Location;

public class Filter extends UnaryPlan {

    private final Expression condition;

    public Filter(Location location, LogicalPlan child, Expression condition) {
        super(location, child);
        this.condition = condition;
    }

    public Expression condition() {
        return condition;
    }

    @Override
    public boolean expressionsResolved() {
        return condition.resolved();
    }

    @Override
    public int hashCode() {
        return Objects.hash(condition, child());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        Filter other = (Filter) obj;
        
        return Objects.equals(condition, other.condition)
                && Objects.equals(child(), other.child());
    }
}
