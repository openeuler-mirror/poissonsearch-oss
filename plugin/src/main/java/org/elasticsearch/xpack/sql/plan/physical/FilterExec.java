/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plan.physical;

import java.util.List;
import java.util.Objects;

import org.elasticsearch.xpack.sql.expression.Attribute;
import org.elasticsearch.xpack.sql.expression.Expression;

public class FilterExec extends UnaryExec implements Unexecutable {

    private final Expression condition;
    // indicates whether the filter is regular or agg-based (HAVING xxx)
    // gets setup automatically and then copied over during cloning
    private final boolean isHaving;

    public FilterExec(PhysicalPlan child, Expression condition) {
        this(child, condition, child instanceof AggregateExec);
    }

    public FilterExec(PhysicalPlan child, Expression condition, boolean isHaving) {
        super(child);
        this.condition = condition;
        this.isHaving = isHaving;
    }

    public Expression condition() {
        return condition;
    }

    public boolean isHaving() {
        return isHaving;
    }

    @Override
    public List<Attribute> output() {
        return child().output();
    }

    @Override
    public int hashCode() {
        return Objects.hash(condition, isHaving, child());
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        
        FilterExec other = (FilterExec) obj;
        return Objects.equals(condition, other.condition)
                && Objects.equals(child(), other.child());
    }
}
