/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.parser;

import org.antlr.v4.runtime.RecognitionException;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.sql.ClientSqlException;
import org.elasticsearch.xpack.sql.tree.Location;

import java.util.Locale;

import static java.lang.String.format;

public class ParsingException extends ClientSqlException {
    private final int line;
    private final int charPositionInLine;

    public ParsingException(String message, RecognitionException cause, int line, int charPositionInLine) {
        super(message, cause);

        this.line = line;
        this.charPositionInLine = charPositionInLine;
    }

    ParsingException(String message, Object... args) {
        this(Location.EMPTY, message, args);
    }

    public ParsingException(Location nodeLocation, String message, Object... args) {
        this(format(Locale.ROOT, message, args), null, nodeLocation.getLineNumber(), nodeLocation.getColumnNumber());
    }

    public int getLineNumber() {
        return line;
    }

    public int getColumnNumber() {
        return charPositionInLine + 1;
    }

    public String getErrorMessage() {
        return super.getMessage();
    }

    @Override
    public RestStatus status() {
        return RestStatus.BAD_REQUEST;
    }

    @Override
    public String getMessage() {
        return format(Locale.ROOT, "line %s:%s: %s", getLineNumber(), getColumnNumber(), getErrorMessage());
    }
}
