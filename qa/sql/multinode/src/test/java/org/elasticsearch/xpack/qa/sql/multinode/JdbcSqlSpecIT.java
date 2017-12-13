/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.multinode;

import org.elasticsearch.xpack.qa.sql.jdbc.SqlSpecTestCase;

public class JdbcSqlSpecIT extends SqlSpecTestCase {
    public JdbcSqlSpecIT(String fileName, String groupName, String testName, Integer lineNumber, String query) {
        super(fileName, groupName, testName, lineNumber, query);
    }
}
