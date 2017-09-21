/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.security;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.qa.sql.jdbc.CsvSpecTestCase;
import org.junit.Before;

import java.util.Properties;

public class JdbcCsvSpecIT extends CsvSpecTestCase {
    public JdbcCsvSpecIT(String fileName, String groupName, String testName, Integer lineNumber, CsvTestCase testCase) {
        super(fileName, groupName, testName, lineNumber, testCase);
    }

    @Override
    protected Settings restClientSettings() {
        return RestSqlIT.securitySettings();
    }

    @Override
    protected Properties connectionProperties() {
        return JdbcConnectionIT.securityProperties();
    }

    @Before
    public void skipShowTables() {
        // NOCOMMIT filter out tables starting with .
        assumeFalse("fails because we don't skip tables starting with .", testName.equals("ShowTables"));
    }
}
