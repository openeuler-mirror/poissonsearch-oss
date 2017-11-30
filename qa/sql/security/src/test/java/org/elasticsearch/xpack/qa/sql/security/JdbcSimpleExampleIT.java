/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.security;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.qa.sql.jdbc.SimpleExampleTestCase;

import java.util.Properties;

public class JdbcSimpleExampleIT extends SimpleExampleTestCase {
    @Override
    protected Settings restClientSettings() {
        return RestSqlIT.securitySettings();
    }

    @Override
    protected Properties connectionProperties() {
        Properties properties = super.connectionProperties();
        properties.putAll(JdbcSecurityIT.adminProperties());
        return properties;
    }
}
