/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression.function.scalar.arithmetic;

import org.elasticsearch.xpack.sql.expression.Expression;
import org.elasticsearch.xpack.sql.expression.Literal;
import org.elasticsearch.xpack.sql.expression.function.scalar.BinaryScalarFunction;
import org.elasticsearch.xpack.sql.expression.function.scalar.arithmetic.BinaryArithmeticProcessor.BinaryArithmeticOperation;
import org.elasticsearch.xpack.sql.expression.function.scalar.processor.definition.ProcessorDefinitions;
import org.elasticsearch.xpack.sql.expression.function.scalar.script.ScriptTemplate;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.type.DataTypeConversion;

import java.util.Locale;
import java.util.Objects;

import static java.lang.String.format;
import static org.elasticsearch.xpack.sql.expression.function.scalar.script.ParamsBuilder.paramsBuilder;

public abstract class ArithmeticFunction extends BinaryScalarFunction {

    private BinaryArithmeticOperation operation;

    ArithmeticFunction(Location location, Expression left, Expression right, BinaryArithmeticOperation operation) {
        super(location, left, right);
        this.operation = operation;
    }

    public BinaryArithmeticOperation operation() {
        return operation;
    }

    @Override
    public DataType dataType() {
        return DataTypeConversion.commonType(left().dataType(), right().dataType());
    }

    @Override
    protected TypeResolution resolveType() {
        if (!childrenResolved()) {
            return new TypeResolution("Unresolved children");
        }
        DataType l = left().dataType();
        DataType r = right().dataType();

        TypeResolution resolution = resolveInputType(l);

        if (resolution == TypeResolution.TYPE_RESOLVED) {
            return resolveInputType(r);
        }
        return resolution;
    }

    protected TypeResolution resolveInputType(DataType inputType) {
        return inputType.isNumeric() ? TypeResolution.TYPE_RESOLVED
                : new TypeResolution("'%s' requires a numeric type, not %s", operation, inputType.sqlName());
    }

    @Override
    protected ScriptTemplate asScriptFrom(ScriptTemplate leftScript, ScriptTemplate rightScript) {
        String op = operation.symbol();
        // escape %
        if (operation == BinaryArithmeticOperation.MOD) {
            op = "%" + op;
        }
        return new ScriptTemplate(format(Locale.ROOT, "(%s) %s (%s)", leftScript.template(), op, rightScript.template()),
                paramsBuilder()
                .script(leftScript.params()).script(rightScript.params())
                .build(), dataType());
    }

    @Override
    protected final BinaryArithmeticProcessorDefinition makeProcessorDefinition() {
        return new BinaryArithmeticProcessorDefinition(location(), this,
                ProcessorDefinitions.toProcessorDefinition(left()),
                ProcessorDefinitions.toProcessorDefinition(right()), operation);
    }

    @Override
    public String name() {
        StringBuilder sb = new StringBuilder();
        sb.append("(");
        sb.append(left());
        if (!(left() instanceof Literal)) {
            sb.insert(1, "(");
            sb.append(")");
        }
        sb.append(" ");
        sb.append(operation);
        sb.append(" ");
        int pos = sb.length();
        sb.append(right());
        if (!(right() instanceof Literal)) {
            sb.insert(pos, "(");
            sb.append(")");
        }
        sb.append(")");
        return sb.toString();
    }

    @Override
    public String toString() {
        return name() + "#" + functionId();
    }

    protected boolean useParanthesis() {
        return !(left() instanceof Literal) || !(right() instanceof Literal);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || obj.getClass() != getClass()) {
            return false;
        }
        ArithmeticFunction other = (ArithmeticFunction) obj;
        return Objects.equals(other.left(), left())
            && Objects.equals(other.right(), right())
            && Objects.equals(other.operation, operation);
    }

    @Override
    public int hashCode() {
        return Objects.hash(left(), right(), operation);
    }
}
