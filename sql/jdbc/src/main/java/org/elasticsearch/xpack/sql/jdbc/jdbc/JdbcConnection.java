/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.jdbc.jdbc;

import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.xpack.sql.jdbc.debug.Debug;
import org.elasticsearch.xpack.sql.jdbc.net.client.JdbcHttpClient;
import org.elasticsearch.xpack.sql.net.client.util.StringUtils;

/**
 * Implementation of {@link Connection} for Elasticsearch.
 */
public class JdbcConnection implements Connection, JdbcWrapper {

    private final String url, userName;
    final JdbcConfiguration cfg;
    final JdbcHttpClient client;

    private boolean closed = false;
    private String catalog;
    private String schema;
    private Properties clientInfo = new Properties();

    public JdbcConnection(JdbcConfiguration connectionInfo) {
        cfg = connectionInfo;
        client = new JdbcHttpClient(connectionInfo);

        url = connectionInfo.asUrl().toExternalForm();
        userName = connectionInfo.userName();
    }

    private void checkOpen() throws SQLException {
        if (isClosed()) {
            throw new SQLException("Connection is closed");
        }
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new JdbcStatement(this, cfg);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        return new JdbcPreparedStatement(this, cfg, sql);
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw new SQLFeatureNotSupportedException("Stored procedures not supported yet");
    }

    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        return sql;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (!autoCommit) {
            new SQLFeatureNotSupportedException("Non auto-commit is not supported");
        }
    }

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return true;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        if (getAutoCommit()) {
            throw new SQLException("Auto-commit is enabled");
        }
        throw new SQLFeatureNotSupportedException("Commit/Rollback not supported");
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        if (getAutoCommit()) {
            throw new SQLException("Auto-commit is enabled");
        }
        throw new SQLFeatureNotSupportedException("Commit/Rollback not supported");
    }

    @Override
    public void close() throws SQLException {
        if (!isClosed()) {
            closed = true;
            Debug.release(cfg);
            client.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        return new JdbcDatabaseMetaData(this);
    }

    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        if (!readOnly) {
            throw new SQLFeatureNotSupportedException("Only read-only mode is supported");
        }
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return true;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
        this.catalog = catalog;
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return catalog;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
        if (TRANSACTION_NONE != level) {
            throw new SQLFeatureNotSupportedException("Transactions not supported");
        }
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return TRANSACTION_NONE;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        // no-op
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkResultSet(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkResultSet(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        checkResultSet(resultSetType, resultSetConcurrency);
        return prepareCall(sql);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("typeMap not supported");
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("typeMap not supported");
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkOpen();
        checkHoldability(holdability);
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return ResultSet.HOLD_CURSORS_OVER_COMMIT;
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Savepoints not supported");
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        checkOpen();
        checkHoldability(resultSetHoldability);
        return createStatement(resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkOpen();
        checkHoldability(resultSetHoldability);
        return prepareStatement(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        checkOpen();
        checkHoldability(resultSetHoldability);
        return prepareCall(sql, resultSetType, resultSetConcurrency);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        checkOpen();
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw new SQLFeatureNotSupportedException("Auto generated keys must be NO_GENERATED_KEYS");
        }
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Autogenerated key not supported");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Autogenerated key not supported");
    }

    @Override
    public Clob createClob() throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Clob not supported yet");
    }

    @Override
    public Blob createBlob() throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Blob not supported yet");
    }

    @Override
    public NClob createNClob() throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("NClob not supported yet");
    }

    @Override
    public SQLXML createSQLXML() throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("SQLXML not supported yet");
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("Negative timeout");
        }
        return client.ping(TimeUnit.SECONDS.toMillis(timeout));
    }

    private void checkOpenClientInfo() throws SQLClientInfoException {
        if (isClosed()) {
            throw new SQLClientInfoException();
        }
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        checkOpenClientInfo();
        if (!StringUtils.hasText(name)) {
            throw new JdbcException("Invalid/Empty name given");
        }
        if (value != null) {
            clientInfo.put(name, value);
        }
        else {
            clientInfo.remove(name);
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        checkOpenClientInfo();
        clientInfo.putAll(properties);
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpenClientInfo();
        return clientInfo.getProperty(name);
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpenClientInfo();
        Properties clone = new Properties();
        clone.putAll(clientInfo);
        return clone;
    }

    @Override
    public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Array not supported yet");
    }

    @Override
    public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        checkOpen();
        throw new SQLFeatureNotSupportedException("Struct not supported yet");
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkOpen();
        this.schema = schema;
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return schema;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("Null executor");
        }
        close();
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        if (executor == null) {
            throw new SQLException("Null executor");
        }
        if (milliseconds < 0) {
            throw new SQLException("Negative milliseconds");
        }
        client.setNetworkTimeout(milliseconds);
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        return (int) client.getNetworkTimeout();
    }

    private void checkResultSet(int resultSetType, int resultSetConcurrency) throws SQLException {
        if (ResultSet.TYPE_FORWARD_ONLY != resultSetType) {
            throw new SQLFeatureNotSupportedException("ResultSet type can only be TYPE_FORWARD_ONLY");
        }
        if (ResultSet.CONCUR_READ_ONLY != resultSetConcurrency) {
            throw new SQLFeatureNotSupportedException("ResultSet concurrency can only be CONCUR_READ_ONLY");
        }
    }

    private void checkHoldability(int resultSetHoldability) throws SQLException {
        if (ResultSet.HOLD_CURSORS_OVER_COMMIT != resultSetHoldability) {
            throw new SQLFeatureNotSupportedException("Holdability can only be HOLD_CURSORS_OVER_COMMIT");
        }
    }

    String getURL() {
        return url;
    }

    String getUserName() {
        return userName;
    }

    // NOCOMMIT should this be one of those wrapped things?
    public int esInfoMajorVersion() throws SQLException {
        return client.serverInfo().majorVersion;
    }

    public int esInfoMinorVersion() throws SQLException {
        return client.serverInfo().minorVersion;
    }
}