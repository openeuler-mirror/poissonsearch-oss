/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.jdbc;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Tests for error messages.
 */
public class ErrorsTestCase extends JdbcIntegrationTestCase {
    public void testSelectFromMissingTable() throws Exception {
        try (Connection c = esJdbc()) {
            SQLException e = expectThrows(SQLException.class, () -> c.prepareStatement("SELECT * from test").executeQuery());
            assertEquals("line 1:15: index [test] does not exist", e.getMessage());
        }
    }
}
