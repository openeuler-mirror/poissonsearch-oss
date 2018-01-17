/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.processor.definition;

import org.elasticsearch.xpack.sql.execution.search.SqlSourceBuilder;
import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.runtime.MatrixFieldProcessor;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.runtime.Processor;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.runtime.SuppliedProcessor;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.tree.NodeInfo;
import java.util.Objects;
import java.util.function.Supplier;

public class AggValueInput extends LeafInput<Supplier<Object>> {

    private final String innerKey;
    private final Processor matrixProcessor;

    public AggValueInput(Location location, Expression expression, Supplier<Object> context, String innerKey) {
        super(location, expression, context);
        this.innerKey = innerKey;
        this.matrixProcessor = innerKey != null ? new MatrixFieldProcessor(innerKey) : null;
    }

    @Override
    protected NodeInfo<AggValueInput> info() {
        return NodeInfo.create(this, AggValueInput::new, expression(), context(), innerKey);
    }

    public String innerKey() {
        return innerKey;
    }

    @Override
    public Processor asProcessor() {
        return new SuppliedProcessor(() -> matrixProcessor != null ? matrixProcessor.process(context().get()) : context().get());
    }

    @Override
    public final boolean supportedByAggsOnlyQuery() {
        return true;
    }

    @Override
    public ProcessorDefinition resolveAttributes(AttributeResolver resolver) {
        return this;
    }

    @Override
    public final void collectFields(SqlSourceBuilder sourceBuilder) {
        // Nothing to collect
    }

    @Override
    public final int depth() {
        return 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(context(), innerKey);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }

        AggValueInput other = (AggValueInput) obj;
        return Objects.equals(context(), other.context())
                && Objects.equals(innerKey, other.innerKey);
    }
}
