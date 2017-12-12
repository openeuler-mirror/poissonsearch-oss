/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.type;

import java.sql.JDBCType;

public class LongType extends AbstractDataType {

    LongType(boolean docValues) {
        super(JDBCType.BIGINT, docValues);
    }

    @Override
    public String esName() {
        return "long";
    }
}
