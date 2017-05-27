/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.parser;

import java.util.Locale;

import org.elasticsearch.common.Strings;
import org.elasticsearch.xpack.sql.parser.SqlBaseParser.IdentifierContext;
import org.elasticsearch.xpack.sql.parser.SqlBaseParser.QualifiedNameContext;
import org.elasticsearch.xpack.sql.parser.SqlBaseParser.TableIdentifierContext;
import org.elasticsearch.xpack.sql.plan.TableIdentifier;
import org.elasticsearch.xpack.sql.tree.Location;

import static java.lang.String.format;

abstract class IdentifierBuilder extends AbstractBuilder {

    @Override
    public TableIdentifier visitTableIdentifier(TableIdentifierContext ctx) {
        String index = null;
        String type = null;

        index = text(ctx.uindex);
        type = text(ctx.utype);
        
        if (index == null) {
            index = text(ctx.index);
            type = text(ctx.type);
        }

        Location source = source(ctx);
        validateIndex(index, source);
        validateType(type, source);

        return new TableIdentifier(source, index, type);
    }

    // see https://github.com/elastic/elasticsearch/issues/6736
    private static void validateIndex(String index, Location source) {
        for (int i = 0; i < index.length(); i++) {
            char c = index.charAt(i);
            if (Character.isUpperCase(c)) {
                throw new ParsingException(source, format(Locale.ROOT, "Invalid index name (needs to be lowercase) %s", index));
            }
            if (c == '.' || c == '\\' || c == '/' || c == '*' || c == '?' || c == '<' || c == '>' || c == '|' || c == ',') {
                throw new ParsingException(source, format(Locale.ROOT, "Illegal character %c in index name %s", c, index));
            }
        }
    }

    private static void validateType(String type, Location source) {
        // no-op
    }

    @Override
    public String visitIdentifier(IdentifierContext ctx) {
        return ctx == null ? null : ctx.quoteIdentifier() != null ? text(ctx.quoteIdentifier()) : text(ctx.unquoteIdentifier());
    }

    @Override
    public String visitQualifiedName(QualifiedNameContext ctx) {
        if (ctx == null) {
            return null;
        }
        // TODO: maybe it makes sense to introduce a dedicated object?
        return Strings.collectionToDelimitedString(visitList(ctx.identifier(), String.class), ".");
    }
}
