/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.processor.definition;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.querydsl.container.ColumnReference;

public class ReferenceInput extends NonExecutableInput<ColumnReference> {
    public ReferenceInput(Expression expression, ColumnReference context) {
        super(expression, context);
    }

    @Override
    public final boolean supportedByAggsOnlyQuery() {
        return false;
    }

    @Override
    public ProcessorDefinition resolveAttributes(AttributeResolver resolver) {
        return this;
    }
}
