/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;

public class StatementWrapper implements Statement {

  protected Statement statement;
  protected Class<?> statementClass;
  protected ConnectionPluginManager pluginManager;

  public StatementWrapper(Statement statement, ConnectionPluginManager pluginManager) {
    if (statement == null) {
      throw new IllegalArgumentException("statement");
    }
    if (pluginManager == null) {
      throw new IllegalArgumentException("pluginManager");
    }

    this.statement = statement;
    this.statementClass = this.statement.getClass();
    this.pluginManager = pluginManager;
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.executeQuery",
        () -> this.statement.executeQuery(sql));
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.executeUpdate",
        () -> this.statement.executeUpdate(sql));
  }

  @Override
  public void close() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.close",
        () -> {
          this.statement.close();
          return null;
        });
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getMaxFieldSize",
        () -> this.statement.getMaxFieldSize());
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setMaxFieldSize",
        () -> {
          this.statement.setMaxFieldSize(max);
          return null;
        });
  }

  @Override
  public int getMaxRows() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getMaxRows",
        () -> this.statement.getMaxRows());
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setMaxRows",
        () -> {
          this.statement.setMaxRows(max);
          return null;
        });
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setEscapeProcessing",
        () -> {
          this.statement.setEscapeProcessing(enable);
          return null;
        });
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getQueryTimeout",
        () -> this.statement.getQueryTimeout());
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setQueryTimeout",
        () -> {
          this.statement.setQueryTimeout(seconds);
          return null;
        });
  }

  @Override
  public void cancel() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.cancel",
        () -> {
          this.statement.cancel();
          return null;
        });
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getWarnings",
        () -> this.statement.getWarnings());
  }

  @Override
  public void clearWarnings() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.clearWarnings",
        () -> {
          this.statement.clearWarnings();
          return null;
        });
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setCursorName",
        () -> {
          this.statement.setCursorName(name);
          return null;
        });
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.execute",
        () -> this.statement.execute(sql));
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getResultSet",
        () -> this.statement.getResultSet());
  }

  @Override
  public int getUpdateCount() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getUpdateCount",
        () -> this.statement.getUpdateCount());
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getMoreResults",
        () -> this.statement.getMoreResults());
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getFetchDirection",
        () -> this.statement.getFetchDirection());
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setFetchDirection",
        () -> {
          this.statement.setFetchDirection(direction);
          return null;
        });
  }

  @Override
  public int getFetchSize() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getFetchSize",
        () -> this.statement.getFetchSize());
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setFetchSize",
        () -> {
          this.statement.setFetchSize(rows);
          return null;
        });
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getResultSetConcurrency",
        () -> this.statement.getResultSetConcurrency());
  }

  @Override
  public int getResultSetType() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getResultSetType",
        () -> this.statement.getResultSetType());
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.addBatch",
        () -> {
          this.statement.addBatch(sql);
          return null;
        });
  }

  @Override
  public void clearBatch() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.clearBatch",
        () -> {
          this.statement.clearBatch();
          return null;
        });
  }

  @Override
  public int[] executeBatch() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.executeBatch",
        () -> this.statement.executeBatch());
  }

  @Override
  public Connection getConnection() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getConnection",
        () -> this.statement.getConnection());
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getMoreResults",
        () -> this.statement.getMoreResults(current));
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getGeneratedKeys",
        () -> this.statement.getGeneratedKeys());
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.executeUpdate",
        () -> this.statement.executeUpdate(sql, autoGeneratedKeys));
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.executeUpdate",
        () -> this.statement.executeUpdate(sql, columnIndexes));
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.executeUpdate",
        () -> this.statement.executeUpdate(sql, columnNames));
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.execute",
        () -> this.statement.execute(sql, autoGeneratedKeys));
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.execute",
        () -> this.statement.execute(sql, columnIndexes));
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.execute",
        () -> this.statement.execute(sql, columnNames));
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.getResultSetHoldability",
        () -> this.statement.getResultSetHoldability());
  }

  @Override
  public boolean isClosed() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.isClosed",
        () -> this.statement.isClosed());
  }

  @Override
  public boolean isPoolable() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.isPoolable",
        () -> this.statement.isPoolable());
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.setPoolable",
        () -> {
          this.statement.setPoolable(poolable);
          return null;
        });
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.closeOnCompletion",
        () -> {
          this.statement.closeOnCompletion();
          return null;
        });
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "Statement.isCloseOnCompletion",
        () -> this.statement.isCloseOnCompletion());
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return this.statement.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return this.statement.isWrapperFor(iface);
  }

  @Override
  public boolean equals(Object obj) {
    return this.statement.equals(obj);
  }

  @Override
  public int hashCode() {
    return this.statement.hashCode();
  }

}
