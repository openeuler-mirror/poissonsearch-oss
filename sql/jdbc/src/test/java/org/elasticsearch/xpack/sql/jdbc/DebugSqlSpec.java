/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.jdbc;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.test.junit.annotations.TestLogging;
import org.elasticsearch.xpack.sql.jdbc.framework.JdbcTestUtils;
import org.elasticsearch.xpack.sql.jdbc.framework.LocalH2;

import java.nio.file.Path;
import java.util.List;

@TestLogging(JdbcTestUtils.SQL_TRACE)
public abstract class DebugSqlSpec extends SqlSpecIT {
    public static LocalH2 H2 = new LocalH2();

    @ParametersFactory(argumentFormatting = PARAM_FORMATTING)
    public static List<Object[]> readScriptSpec() throws Exception {

        Parser parser = specParser();
        return readScriptSpec("/debug.sql-spec", parser);
    }

    public DebugSqlSpec(String groupName, String testName, Integer lineNumber, Path source, String query) {
        super(groupName, testName, lineNumber, source, query);
    }

    @Override
    protected boolean logEsResultSet() {
        return true;
    }
}