/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.querydsl.container;

public interface FieldReference extends ColumnReference {

    @Override
    default int depth() {
        return 0;
    }

    /**
     * Field name.
     * 
     * @return field name.
     */
    String name();
}
