/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.analysis;

import org.elasticsearch.xpack.sql.tree.Node;

public class UnknownFunctionException extends AnalysisException {

    public UnknownFunctionException(String function, Node<?> source) {
        super(source, "Cannot resolve function %s", function);
    }
}
