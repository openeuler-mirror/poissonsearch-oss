/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.sql.proto;

import java.util.Locale;

/**
 * SQL protocol mode
 */
public enum Mode {
    PLAIN,
    JDBC;

    public static Mode fromString(String mode) {
        if (mode == null) {
            return PLAIN;
        }
        return Mode.valueOf(mode.toUpperCase(Locale.ROOT));
    }


    @Override
    public String toString() {
        return this.name().toLowerCase(Locale.ROOT);
    }
}
