/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.jdbc;

import com.carrotsearch.randomizedtesting.annotations.ParametersFactory;

import org.elasticsearch.xpack.sql.jdbc.jdbc.JdbcConfiguration;
import org.elasticsearch.xpack.sql.util.CollectionUtils;
import org.junit.ClassRule;

import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import java.util.Properties;

/**
 * Tests comparing sql queries executed against our jdbc client
 * with those executed against H2's jdbc client.
 */
public abstract class SqlSpecTestCase extends SpecBaseIntegrationTestCase {
    private String query;

    @ClassRule
    public static LocalH2 H2 = new LocalH2();

    @ParametersFactory(argumentFormatting = PARAM_FORMATTING)
    public static List<Object[]> readScriptSpec() throws Exception {
        Parser parser = specParser();
        return CollectionUtils.combine(
                readScriptSpec("/select.sql-spec", parser),
                readScriptSpec("/filter.sql-spec", parser),
                readScriptSpec("/datetime.sql-spec", parser),
                readScriptSpec("/math.sql-spec", parser),
                readScriptSpec("/agg.sql-spec", parser),
                readScriptSpec("/arithmetic.sql-spec", parser)
                );
    }

    private static class SqlSpecParser implements Parser {
        @Override
        public Object parse(String line) {
            return line.endsWith(";") ? line.substring(0, line.length() - 1) : line;
        }
    }

    static SqlSpecParser specParser() {
        return new SqlSpecParser();
    }

    public SqlSpecTestCase(String fileName, String groupName, String testName, Integer lineNumber, String query) {
        super(fileName, groupName, testName, lineNumber);
        this.query = query;
    }

    @Override
    protected final void doTest() throws Throwable {
        try (Connection h2 = H2.get(); 
             Connection es = esJdbc()) {

            ResultSet expected, elasticResults;
            expected = executeJdbcQuery(h2, query);
            elasticResults = executeJdbcQuery(es, query);

            assertResults(expected, elasticResults);
        }
    }

    // TODO: use UTC for now until deciding on a strategy for handling date extraction
    @Override
    protected Properties connectionProperties() {
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty(JdbcConfiguration.TIME_ZONE, "UTC");
        return connectionProperties;
    }
}