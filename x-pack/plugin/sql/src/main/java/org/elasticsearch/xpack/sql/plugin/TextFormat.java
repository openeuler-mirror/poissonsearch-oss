/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.plugin;

import org.elasticsearch.common.Strings;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.xpack.sql.action.SqlQueryResponse;
import org.elasticsearch.xpack.sql.action.CliFormatter;
import org.elasticsearch.xpack.sql.proto.ColumnInfo;
import org.elasticsearch.xpack.sql.session.Cursor;
import org.elasticsearch.xpack.sql.session.Cursors;
import org.elasticsearch.xpack.sql.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Templating class for displaying SQL responses in text formats.
 */

// TODO are we sure toString is correct here? What about dates that come back as longs.
// Tracked by https://github.com/elastic/x-pack-elasticsearch/issues/3081
enum TextFormat {

    /**
     * Default text writer.
     *
     * The implementation is a bit weird since state needs to be passed around, namely the formatter
     * since it is initialized based on the first page of data.
     * To avoid leaking the formatter, it gets discovered again in the wrapping method to attach it
     * to the next cursor and so on.
     */
    PLAIN_TEXT() {
        @Override
        String format(Cursor cursor, RestRequest request, SqlQueryResponse response) {
            final CliFormatter formatter;
            if (cursor instanceof CliFormatterCursor) {
                formatter = ((CliFormatterCursor) cursor).getCliFormatter();
                return formatter.formatWithoutHeader(response.rows());
            } else {
                formatter = new CliFormatter(response.columns(), response.rows());
                return formatter.formatWithHeader(response.columns(), response.rows());
            }
        }

        @Override
        Cursor wrapCursor(Cursor oldCursor, SqlQueryResponse response) {
            CliFormatter formatter = (oldCursor instanceof CliFormatterCursor) ?
                    ((CliFormatterCursor) oldCursor).getCliFormatter() : new CliFormatter(response.columns(), response.rows());
            return CliFormatterCursor.wrap(super.wrapCursor(oldCursor, response), formatter);
        }

        @Override
        String shortName() {
            return "txt";
        }

        @Override
        String contentType() {
            return "text/plain";
        }

        @Override
        protected String delimiter() {
            throw new UnsupportedOperationException();
        }

        @Override
        protected String eol() {
            throw new UnsupportedOperationException();
        }
    },

    /**
     * Comma Separated Values implementation.
     *
     * Based on:
     * https://tools.ietf.org/html/rfc4180
     * https://www.iana.org/assignments/media-types/text/csv
     * https://www.w3.org/TR/sparql11-results-csv-tsv/
     *
     */
    CSV() {

        @Override
        protected String delimiter() {
            return ",";
        }

        @Override
        protected String eol() {
            //LFCR
            return "\r\n";
        }

        @Override
        String shortName() {
            return "csv";
        }

        @Override
        String contentType() {
            return "text/csv";
        }

        @Override
        String contentType(RestRequest request) {
            return contentType() + "; charset=utf-8; header=" + (hasHeader(request) ? "present" : "absent");
        }

        @Override
        String maybeEscape(String value) {
            boolean needsEscaping = false;

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == ',' || c == '\n' || c == '\r') {
                    needsEscaping = true;
                    break;
                }
            }

            if (needsEscaping) {
                StringBuilder sb = new StringBuilder();

                sb.append('"');
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (value.charAt(i) == '"') {
                        sb.append('"');
                    }
                    sb.append(c);
                }
                sb.append('"');
                value = sb.toString();
            }
            return value;
        }

        @Override
        boolean hasHeader(RestRequest request) {
            String header = request.param("header");
            if (header == null) {
                List<String> values = request.getAllHeaderValues("Accept");
                if (values != null) {
                    // header is a parameter specified by ; so try breaking it down
                    for (String value : values) {
                        String[] params = Strings.tokenizeToStringArray(value, ";");
                        for (String param : params) {
                            if (param.toLowerCase(Locale.ROOT).equals("header=absent")) {
                                return false;
                            }
                        }
                    }
                }
                return true;
            } else {
                return !header.toLowerCase(Locale.ROOT).equals("absent");
            }
        }
    },

    TSV() {
        @Override
        protected String delimiter() {
            return "\t";
        }

        @Override
        protected String eol() {
            // only CR
            return "\n";
        }

        @Override
        String shortName() {
            return "tsv";
        }

        @Override
        String contentType() {
            return "text/tab-separated-values";
        }

        @Override
        String contentType(RestRequest request) {
            return contentType() + "; charset=utf-8";
        }

        @Override
        String maybeEscape(String value) {
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '\n' :
                        sb.append("\\n");
                        break;
                    case '\t' :
                        sb.append("\\t");
                        break;
                    default:
                        sb.append(c);
                }
            }

            return sb.toString();
        }
    };


    String format(Cursor cursor, RestRequest request, SqlQueryResponse response) {
        StringBuilder sb = new StringBuilder();

        boolean header = hasHeader(request);

        if (header) {
            row(sb, response.columns(), ColumnInfo::name);
        }

        for (List<Object> row : response.rows()) {
            row(sb, row, f -> Objects.toString(f, StringUtils.EMPTY));
        }

        return sb.toString();
    }

    boolean hasHeader(RestRequest request) {
        return true;
    }

    Cursor wrapCursor(Cursor oldCursor, SqlQueryResponse response) {
        return Cursors.decodeFromString(response.cursor());
    }

    static TextFormat fromMediaTypeOrFormat(String accept) {
        for (TextFormat text : values()) {
            String contentType = text.contentType();
            if (contentType.equalsIgnoreCase(accept)
                    || accept.toLowerCase(Locale.ROOT).startsWith(contentType + ";")
                    || text.shortName().equalsIgnoreCase(accept)) {
                return text;
            }
        }

        throw new IllegalArgumentException("invalid format [" + accept + "]");
    }

    /**
     * Short name typically used by format parameter.
     * Can differ from the IANA mime type.
     */
    abstract String shortName();


    /**
     * Formal IANA mime type.
     */
    abstract String contentType();

    /**
     * Content type depending on the request.
     * Might be used by some formatters (like CSV) to specify certain metadata like
     * whether the header is returned or not.
     */
    String contentType(RestRequest request) {
        return contentType();
    }

    // utility method for consuming a row.
    <F> void row(StringBuilder sb, List<F> row, Function<F, String> toString) {
        for (int i = 0; i < row.size(); i++) {
            sb.append(maybeEscape(toString.apply(row.get(i))));
            if (i < row.size() - 1) {
                sb.append(delimiter());
            }
        }
        sb.append(eol());
    }

    /**
     * Delimiter between fields
     */
    protected abstract String delimiter();

    /**
     * String indicating end-of-line or row.
     */
    protected abstract String eol();

    /**
     * Method used for escaping (if needed) a given value.
     */
    String maybeEscape(String value) {
        return value;
    }
}
