/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.expression;

import org.elasticsearch.xpack.sql.capabilities.Unresolvable;
import org.elasticsearch.xpack.sql.capabilities.UnresolvedException;
import org.elasticsearch.xpack.sql.tree.Location;
import org.elasticsearch.xpack.sql.type.DataType;
import org.elasticsearch.xpack.sql.util.CollectionUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import static java.lang.String.format;

public class UnresolvedAttribute extends Attribute implements Unresolvable {

    private final String unresolvedMsg;
    private final Object resolutionMetadata;

    public UnresolvedAttribute(Location location, String name) {
        this(location, name, null);
    }

    public UnresolvedAttribute(Location location, String name, String qualifier) {
        this(location, name, qualifier, null);
    }

    public UnresolvedAttribute(Location location, String name, String qualifier, String unresolvedMessage) {
        this(location, name, qualifier, null, unresolvedMessage, null);
    }

    public UnresolvedAttribute(Location location, String name, String qualifier, ExpressionId id, String unresolvedMessage, Object resolutionMetadata) {
        super(location, name, qualifier, id);
        this.unresolvedMsg = unresolvedMessage == null ? errorMessage(qualifiedName(), null) : unresolvedMessage;
        this.resolutionMetadata = resolutionMetadata;
    }


    public Object resolutionMetadata() {
        return resolutionMetadata;
    }

    @Override
    public boolean resolved() {
        return false;
    }

    @Override
    protected Attribute clone(Location location, String name, DataType dataType, String qualifier, boolean nullable, ExpressionId id, boolean synthetic) {
        return this;
    }

    public UnresolvedAttribute withUnresolvedMessage(String unresolvedMsg) {
        return new UnresolvedAttribute(location(), name(), qualifier(), id(), unresolvedMsg, resolutionMetadata());
    }

    @Override
    public DataType dataType() {
        throw new UnresolvedException("dataType", this);
    }

    @Override
    public String nodeString() {
        return format(Locale.ROOT, "unknown column '%s'", name());
    }

    @Override
    public String toString() {
        return UNRESOLVED_PREFIX + qualifiedName();
    }

    @Override
    protected String label() {
        return UNRESOLVED_PREFIX;
    }

    @Override
    public String unresolvedMessage() {
        return unresolvedMsg;
    }

    public static String errorMessage(String name, List<String> potentialMatches) {
        String msg = "Unknown column [" + name + "]";
        if (!CollectionUtils.isEmpty(potentialMatches)) {
            msg += ", did you mean " + (potentialMatches.size() == 1 ? "[" + potentialMatches.get(0) + "]": "any of " + potentialMatches.toString()) + "?";
        }
        return msg;
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), resolutionMetadata, unresolvedMsg);
    }

    @Override
    public boolean equals(Object obj) {
        if (super.equals(obj)) {
            UnresolvedAttribute ua = (UnresolvedAttribute) obj;
            return Objects.equals(resolutionMetadata, ua.resolutionMetadata) && Objects.equals(unresolvedMsg, ua.unresolvedMsg);
        }
        return false;
    }
}