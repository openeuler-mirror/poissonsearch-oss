/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.aggregate;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.function.Function;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.util.CollectionUtils;

import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;

public abstract class AggregateFunction extends Function {

    private final Expression field;
    private final List<Expression> parameters;

    private AggregateFunctionAttribute lazyAttribute;

    AggregateFunction(Location location, Expression field) {
        this(location, field, emptyList());
    }

    AggregateFunction(Location location, Expression field, List<Expression> parameters) {
        super(location, CollectionUtils.combine(singletonList(field), parameters));
        this.field = field;
        this.parameters = parameters;
    }

    public Expression field() {
        return field;
    }

    public List<Expression> parameters() {
        return parameters;
    }

    @Override
    public AggregateFunctionAttribute toAttribute() {
        if (lazyAttribute == null) {
            // this is highly correlated with QueryFolder$FoldAggregate#addFunction (regarding the function name within the querydsl)
            lazyAttribute = new AggregateFunctionAttribute(location(), name(), dataType(), id(), functionId(), null);
        }
        return lazyAttribute;
    }
}
