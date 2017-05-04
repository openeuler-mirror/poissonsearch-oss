/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.support.mapper.expressiondsl;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;

/**
 * An expression that evaluates to <code>true</code> if-and-only-if all its children
 * evaluate to <code>true</code>.
 * An <em>all</em> expression with no children is always <code>true</code>.
 */
public final class AllExpression implements RoleMapperExpression {

    static final String NAME = "all";

    private final List<RoleMapperExpression> elements;

    AllExpression(List<RoleMapperExpression> elements) {
        assert elements != null;
        this.elements = elements;
    }

    AllExpression(StreamInput in) throws IOException {
        this(ExpressionParser.readExpressionList(in));
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        ExpressionParser.writeExpressionList(elements, out);
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    public boolean match(Map<String, Object> object) {
        return elements.stream().allMatch(RoleMapperExpression.predicate(object));
    }

    public List<RoleMapperExpression> getElements() {
        return Collections.unmodifiableList(elements);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        final AllExpression that = (AllExpression) o;
        return this.elements.equals(that.elements);
    }

    @Override
    public int hashCode() {
        return elements.hashCode();
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.startArray(ExpressionParser.Fields.ALL.getPreferredName());
        for (RoleMapperExpression e : elements) {
            e.toXContent(builder, params);
        }
        builder.endArray();
        return builder.endObject();
    }
}
