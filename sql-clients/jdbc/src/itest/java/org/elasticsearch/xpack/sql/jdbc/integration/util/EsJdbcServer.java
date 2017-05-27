/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.jdbc.integration.util;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

import org.elasticsearch.client.Client;
import org.elasticsearch.xpack.sql.integration.es.LocalEs;
import org.elasticsearch.xpack.sql.jdbc.integration.server.JdbcHttpServer;
import org.elasticsearch.xpack.sql.jdbc.integration.util.JdbcTemplate.JdbcSupplier;
import org.elasticsearch.xpack.sql.jdbc.jdbc.JdbcDriver;
import org.junit.rules.ExternalResource;

import static org.junit.Assert.assertNotNull;

public class EsJdbcServer extends ExternalResource implements JdbcSupplier<Connection> {

    private final LocalEs es;
    private JdbcHttpServer server;
    private String jdbcUrl;
    private JdbcDriver driver;
    private final Properties properties;

    public EsJdbcServer(boolean remote, boolean debug) {
        es = (remote ? null : new LocalEs());
        properties = new Properties();
        if (debug) {
            properties.setProperty("debug", "true");
        }
    }

    public void start() throws Throwable {
        before();
    }

    public void stop() {
        after();
    }

    @Override
    protected void before() throws Throwable {
        if (es != null) {
            es.start();
        }

        server = new JdbcHttpServer(es != null ? es.client() : null);
        driver = new JdbcDriver();

        server.start(0);
        jdbcUrl = server.url();
    }

    @Override
    protected void after() {
        server.stop();
        server = null;
        if (es != null) {
            es.stop();
        }
    }

    public Connection jdbc() throws SQLException {
        assertNotNull("ES JDBC Driver is null - make sure ES is properly run as a @ClassRule", driver);
        return driver.connect(jdbcUrl, properties);
    }

    public Client client() {
        assertNotNull("ES JDBC Server is null - make sure ES is properly run as a @ClassRule", driver);
        return server.client();
    }
}