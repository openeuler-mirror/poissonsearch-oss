/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.jdbc;

import org.elasticsearch.common.CheckedSupplier;
import org.junit.rules.ExternalResource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class LocalH2 extends ExternalResource implements CheckedSupplier<Connection, SQLException> {
    static {
        try {
            // Initialize h2 so we can use it for testing
            Class.forName("org.h2.Driver");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates an in memory anonymous database and returns the only connection to it.
     * Closing the connection will remove the db.
     */
    public static Connection anonymousDb() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:;DATABASE_TO_UPPER=false;ALIAS_COLUMN_NAME=true");
    }

    private static final Properties DEFAULTS = new Properties();

    private final String url;
    // H2 in-memory will keep the db alive as long as this connection is opened
    private Connection keepAlive;

    /*
     * The syntax on the connection string is fairly particular:
     *      mem:; creates an anonymous database in memory. The `;` is
     *              technically the separator that comes after the name.
     *      DATABASE_TO_UPPER=false turns *off* H2's Oracle-like habit
     *              of upper-casing everything that isn't quoted.
     *      ALIAS_COLUMN_NAME=true turn *on* returning alias names in
     *              result set metadata which is what most DBs do except
     *              for MySQL and, by default, H2. Our jdbc driver does it.
     */
    // http://www.h2database.com/html/features.html#in_memory_databases
    public LocalH2() {
        this.url = "jdbc:h2:mem:essql;DATABASE_TO_UPPER=false;ALIAS_COLUMN_NAME=true";
    }

    @Override
    protected void before() throws Throwable {
        keepAlive = get();
        keepAlive.createStatement().execute("RUNSCRIPT FROM 'classpath:/setup_test_emp.sql'");
    }

    @Override
    protected void after() {
        try {
            keepAlive.close();
        } catch (SQLException ex) {
            // close
        }
    }

    @Override
    public Connection get() throws SQLException {
        return DriverManager.getConnection(url, DEFAULTS);
    }
}
