/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.querydsl.container;

import org.elasticsearch.xpack.sql.expression.function.scalar.ColumnProcessor;

public class ProcessingRef implements Reference {

    private final ColumnProcessor processor;
    private final Reference ref;

    public ProcessingRef(ColumnProcessor processor, Reference ref) {
        this.processor = processor;
        this.ref = ref;
    }

    public ColumnProcessor processor() {
        return processor;
    }

    public Reference ref() {
        return ref;
    }

    @Override
    public String toString() {
        return processor + "(" + ref + ")";
    }
}
