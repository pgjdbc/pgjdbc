/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

public class CallableStatementWrapper implements CallableStatement {

  protected CallableStatement statement;
  protected Class<?> statementClass;
  protected ConnectionPluginManager pluginManager;

  public CallableStatementWrapper(CallableStatement statement,
      ConnectionPluginManager pluginManager) {
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
  public void registerOutParameter(int parameterIndex, int sqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterIndex, sqlType);
          return null;
        });
  }

  @Override
  public void registerOutParameter(int parameterIndex, int sqlType, int scale) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterIndex, sqlType, scale);
          return null;
        });
  }

  @Override
  public boolean wasNull() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.wasNull",
        () -> this.statement.wasNull());
  }

  @Override
  public String getString(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getString",
        () -> this.statement.getString(parameterIndex));
  }

  @Override
  public boolean getBoolean(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBoolean",
        () -> this.statement.getBoolean(parameterIndex));
  }

  @Override
  public byte getByte(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getByte",
        () -> this.statement.getByte(parameterIndex));
  }

  @Override
  public short getShort(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getShort",
        () -> this.statement.getShort(parameterIndex));
  }

  @Override
  public int getInt(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getInt",
        () -> this.statement.getInt(parameterIndex));
  }

  @Override
  public long getLong(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getLong",
        () -> this.statement.getLong(parameterIndex));
  }

  @Override
  public float getFloat(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getFloat",
        () -> this.statement.getFloat(parameterIndex));
  }

  @Override
  public double getDouble(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getDouble",
        () -> this.statement.getDouble(parameterIndex));
  }

  @Override
  @SuppressWarnings("deprecation")
  public BigDecimal getBigDecimal(int parameterIndex, int scale) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBigDecimal",
        () -> this.statement.getBigDecimal(parameterIndex, scale));
  }

  @Override
  public byte[] getBytes(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBytes",
        () -> this.statement.getBytes(parameterIndex));
  }

  @Override
  public Date getDate(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getDate",
        () -> this.statement.getDate(parameterIndex));
  }

  @Override
  public Time getTime(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTime",
        () -> this.statement.getTime(parameterIndex));
  }

  @Override
  public Timestamp getTimestamp(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTimestamp",
        () -> this.statement.getTimestamp(parameterIndex));
  }

  @Override
  public Object getObject(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getObject",
        () -> this.statement.getObject(parameterIndex));
  }

  @Override
  public BigDecimal getBigDecimal(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBigDecimal",
        () -> this.statement.getBigDecimal(parameterIndex));
  }

  @Override
  public Object getObject(int parameterIndex, Map<String, Class<?>> map) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getObject",
        () -> this.statement.getObject(parameterIndex, map));
  }

  @Override
  public Ref getRef(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getRef",
        () -> this.statement.getRef(parameterIndex));
  }

  @Override
  public Blob getBlob(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBlob",
        () -> this.statement.getBlob(parameterIndex));
  }

  @Override
  public Clob getClob(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getClob",
        () -> this.statement.getClob(parameterIndex));
  }

  @Override
  public Array getArray(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getArray",
        () -> this.statement.getArray(parameterIndex));
  }

  @Override
  public Date getDate(int parameterIndex, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getDate",
        () -> this.statement.getDate(parameterIndex, cal));
  }

  @Override
  public Time getTime(int parameterIndex, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTime",
        () -> this.statement.getTime(parameterIndex, cal));
  }

  @Override
  public Timestamp getTimestamp(int parameterIndex, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTimestamp",
        () -> this.statement.getTimestamp(parameterIndex, cal));
  }

  @Override
  public void registerOutParameter(int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterIndex, sqlType, typeName);
          return null;
        });
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterName, sqlType);
          return null;
        });
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType, int scale)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterName, sqlType, scale);
          return null;
        });
  }

  @Override
  public void registerOutParameter(String parameterName, int sqlType, String typeName)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterName, sqlType, typeName);
          return null;
        });
  }

  @Override
  public URL getURL(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getURL",
        () -> this.statement.getURL(parameterIndex));
  }

  @Override
  public void setURL(String parameterName, URL val) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setURL",
        () -> {
          this.statement.setURL(parameterName, val);
          return null;
        });
  }

  @Override
  public void setNull(String parameterName, int sqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNull",
        () -> {
          this.statement.setNull(parameterName, sqlType);
          return null;
        });
  }

  @Override
  public void setBoolean(String parameterName, boolean x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBoolean",
        () -> {
          this.statement.setBoolean(parameterName, x);
          return null;
        });
  }

  @Override
  public void setByte(String parameterName, byte x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setByte",
        () -> {
          this.statement.setByte(parameterName, x);
          return null;
        });
  }

  @Override
  public void setShort(String parameterName, short x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setShort",
        () -> {
          this.statement.setShort(parameterName, x);
          return null;
        });
  }

  @Override
  public void setInt(String parameterName, int x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setInt",
        () -> {
          this.statement.setInt(parameterName, x);
          return null;
        });
  }

  @Override
  public void setLong(String parameterName, long x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setLong",
        () -> {
          this.statement.setLong(parameterName, x);
          return null;
        });
  }

  @Override
  public void setFloat(String parameterName, float x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setFloat",
        () -> {
          this.statement.setFloat(parameterName, x);
          return null;
        });
  }

  @Override
  public void setDouble(String parameterName, double x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setDouble",
        () -> {
          this.statement.setDouble(parameterName, x);
          return null;
        });
  }

  @Override
  public void setBigDecimal(String parameterName, BigDecimal x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBigDecimal",
        () -> {
          this.statement.setBigDecimal(parameterName, x);
          return null;
        });
  }

  @Override
  public void setString(String parameterName, String x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setString",
        () -> {
          this.statement.setString(parameterName, x);
          return null;
        });
  }

  @Override
  public void setBytes(String parameterName, byte[] x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBytes",
        () -> {
          this.statement.setBytes(parameterName, x);
          return null;
        });
  }

  @Override
  public void setDate(String parameterName, Date x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setDate",
        () -> {
          this.statement.setDate(parameterName, x);
          return null;
        });
  }

  @Override
  public void setTime(String parameterName, Time x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTime",
        () -> {
          this.statement.setTime(parameterName, x);
          return null;
        });
  }

  @Override
  public void setTimestamp(String parameterName, Timestamp x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTimestamp",
        () -> {
          this.statement.setTimestamp(parameterName, x);
          return null;
        });
  }

  @Override
  public void setAsciiStream(String parameterName, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setAsciiStream",
        () -> {
          this.statement.setAsciiStream(parameterName, x, length);
          return null;
        });
  }

  @Override
  public void setBinaryStream(String parameterName, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBinaryStream",
        () -> {
          this.statement.setBinaryStream(parameterName, x, length);
          return null;
        });
  }

  @Override
  public void setObject(String parameterName, Object x, int targetSqlType, int scale)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterName, x, targetSqlType, scale);
          return null;
        });
  }

  @Override
  public void setObject(String parameterName, Object x, int targetSqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterName, x, targetSqlType);
          return null;
        });
  }

  @Override
  public void setObject(String parameterName, Object x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterName, x);
          return null;
        });
  }

  @Override
  public void setCharacterStream(String parameterName, Reader reader, int length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setCharacterStream",
        () -> {
          this.statement.setCharacterStream(parameterName, reader, length);
          return null;
        });
  }

  @Override
  public void setDate(String parameterName, Date x, Calendar cal) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setDate",
        () -> {
          this.statement.setDate(parameterName, x, cal);
          return null;
        });
  }

  @Override
  public void setTime(String parameterName, Time x, Calendar cal) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTime",
        () -> {
          this.statement.setTime(parameterName, x, cal);
          return null;
        });
  }

  @Override
  public void setTimestamp(String parameterName, Timestamp x, Calendar cal) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTimestamp",
        () -> {
          this.statement.setTimestamp(parameterName, x, cal);
          return null;
        });
  }

  @Override
  public void setNull(String parameterName, int sqlType, String typeName) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNull",
        () -> {
          this.statement.setNull(parameterName, sqlType, typeName);
          return null;
        });
  }

  @Override
  public String getString(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getString",
        () -> this.statement.getString(parameterName));
  }

  @Override
  public boolean getBoolean(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBoolean",
        () -> this.statement.getBoolean(parameterName));
  }

  @Override
  public byte getByte(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getByte",
        () -> this.statement.getByte(parameterName));
  }

  @Override
  public short getShort(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getShort",
        () -> this.statement.getShort(parameterName));
  }

  @Override
  public int getInt(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getInt",
        () -> this.statement.getInt(parameterName));
  }

  @Override
  public long getLong(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getLong",
        () -> this.statement.getLong(parameterName));
  }

  @Override
  public float getFloat(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getFloat",
        () -> this.statement.getFloat(parameterName));
  }

  @Override
  public double getDouble(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getDouble",
        () -> this.statement.getDouble(parameterName));
  }

  @Override
  public byte[] getBytes(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBytes",
        () -> this.statement.getBytes(parameterName));
  }

  @Override
  public Date getDate(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getDate",
        () -> this.statement.getDate(parameterName));
  }

  @Override
  public Time getTime(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTime",
        () -> this.statement.getTime(parameterName));
  }

  @Override
  public Timestamp getTimestamp(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTimestamp",
        () -> this.statement.getTimestamp(parameterName));
  }

  @Override
  public Object getObject(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getObject",
        () -> this.statement.getObject(parameterName));
  }

  @Override
  public BigDecimal getBigDecimal(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBigDecimal",
        () -> this.statement.getBigDecimal(parameterName));
  }

  @Override
  public Object getObject(String parameterName, Map<String, Class<?>> map) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getObject",
        () -> this.statement.getObject(parameterName, map));
  }

  @Override
  public Ref getRef(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getRef",
        () -> this.statement.getRef(parameterName));
  }

  @Override
  public Blob getBlob(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getBlob",
        () -> this.statement.getBlob(parameterName));
  }

  @Override
  public Clob getClob(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getClob",
        () -> this.statement.getClob(parameterName));
  }

  @Override
  public Array getArray(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getArray",
        () -> this.statement.getArray(parameterName));
  }

  @Override
  public Date getDate(String parameterName, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getDate",
        () -> this.statement.getDate(parameterName, cal));
  }

  @Override
  public Time getTime(String parameterName, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTime",
        () -> this.statement.getTime(parameterName, cal));
  }

  @Override
  public Timestamp getTimestamp(String parameterName, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getTimestamp",
        () -> this.statement.getTimestamp(parameterName, cal));
  }

  @Override
  public URL getURL(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getURL",
        () -> this.statement.getURL(parameterName));
  }

  @Override
  public RowId getRowId(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getRowId",
        () -> this.statement.getRowId(parameterIndex));
  }

  @Override
  public RowId getRowId(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getRowId",
        () -> this.statement.getRowId(parameterName));
  }

  @Override
  public void setRowId(String parameterName, RowId x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setRowId",
        () -> {
          this.statement.setRowId(parameterName, x);
          return null;
        });
  }

  @Override
  public void setNString(String parameterName, String value) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNString",
        () -> {
          this.statement.setNString(parameterName, value);
          return null;
        });
  }

  @Override
  public void setNCharacterStream(String parameterName, Reader value, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNCharacterStream",
        () -> {
          this.statement.setNCharacterStream(parameterName, value, length);
          return null;
        });
  }

  @Override
  public void setNClob(String parameterName, NClob value) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNClob",
        () -> {
          this.statement.setNClob(parameterName, value);
          return null;
        });
  }

  @Override
  public void setClob(String parameterName, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setClob",
        () -> {
          this.statement.setClob(parameterName, reader, length);
          return null;
        });
  }

  @Override
  public void setBlob(String parameterName, InputStream inputStream, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBlob",
        () -> {
          this.statement.setBlob(parameterName, inputStream, length);
          return null;
        });
  }

  @Override
  public void setNClob(String parameterName, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNClob",
        () -> {
          this.statement.setNClob(parameterName, reader, length);
          return null;
        });
  }

  @Override
  public NClob getNClob(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getNClob",
        () -> this.statement.getNClob(parameterIndex));
  }

  @Override
  public NClob getNClob(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getNClob",
        () -> this.statement.getNClob(parameterName));
  }

  @Override
  public void setSQLXML(String parameterName, SQLXML xmlObject) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setSQLXML",
        () -> {
          this.statement.setSQLXML(parameterName, xmlObject);
          return null;
        });
  }

  @Override
  public SQLXML getSQLXML(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getSQLXML",
        () -> this.statement.getSQLXML(parameterIndex));
  }

  @Override
  public SQLXML getSQLXML(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getSQLXML",
        () -> this.statement.getSQLXML(parameterName));
  }

  @Override
  public String getNString(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getNString",
        () -> this.statement.getNString(parameterIndex));
  }

  @Override
  public String getNString(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getNString",
        () -> this.statement.getNString(parameterName));
  }

  @Override
  public Reader getNCharacterStream(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getNCharacterStream",
        () -> this.statement.getNCharacterStream(parameterIndex));
  }

  @Override
  public Reader getNCharacterStream(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getNCharacterStream",
        () -> this.statement.getNCharacterStream(parameterName));
  }

  @Override
  public Reader getCharacterStream(int parameterIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getCharacterStream",
        () -> this.statement.getCharacterStream(parameterIndex));
  }

  @Override
  public Reader getCharacterStream(String parameterName) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getCharacterStream",
        () -> this.statement.getCharacterStream(parameterName));
  }

  @Override
  public void setBlob(String parameterName, Blob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBlob",
        () -> {
          this.statement.setBlob(parameterName, x);
          return null;
        });
  }

  @Override
  public void setClob(String parameterName, Clob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setClob",
        () -> {
          this.statement.setClob(parameterName, x);
          return null;
        });
  }

  @Override
  public void setAsciiStream(String parameterName, InputStream x, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setAsciiStream",
        () -> {
          this.statement.setAsciiStream(parameterName, x, length);
          return null;
        });
  }

  @Override
  public void setBinaryStream(String parameterName, InputStream x, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBinaryStream",
        () -> {
          this.statement.setBinaryStream(parameterName, x, length);
          return null;
        });
  }

  @Override
  public void setCharacterStream(String parameterName, Reader reader, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setCharacterStream",
        () -> {
          this.statement.setCharacterStream(parameterName, reader, length);
          return null;
        });
  }

  @Override
  public void setAsciiStream(String parameterName, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setAsciiStream",
        () -> {
          this.statement.setAsciiStream(parameterName, x);
          return null;
        });
  }

  @Override
  public void setBinaryStream(String parameterName, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBinaryStream",
        () -> {
          this.statement.setBinaryStream(parameterName, x);
          return null;
        });
  }

  @Override
  public void setCharacterStream(String parameterName, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setCharacterStream",
        () -> {
          this.statement.setCharacterStream(parameterName, reader);
          return null;
        });
  }

  @Override
  public void setNCharacterStream(String parameterName, Reader value) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNCharacterStream",
        () -> {
          this.statement.setNCharacterStream(parameterName, value);
          return null;
        });
  }

  @Override
  public void setClob(String parameterName, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setClob",
        () -> {
          this.statement.setClob(parameterName, reader);
          return null;
        });
  }

  @Override
  public void setBlob(String parameterName, InputStream inputStream) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBlob",
        () -> {
          this.statement.setBlob(parameterName, inputStream);
          return null;
        });
  }

  @Override
  public void setNClob(String parameterName, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNClob",
        () -> {
          this.statement.setNClob(parameterName, reader);
          return null;
        });
  }

  @Override
  public <T> T getObject(int parameterIndex, Class<T> type) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getObject",
        () -> this.statement.getObject(parameterIndex, type));
  }

  @Override
  public <T> T getObject(String parameterName, Class<T> type) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getObject",
        () -> this.statement.getObject(parameterName, type));
  }

  @Override
  public void setObject(String parameterName, Object x, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterName, x, targetSqlType, scaleOrLength);
          return null;
        });
  }

  @Override
  public void setObject(String parameterName, Object x, SQLType targetSqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterName, x, targetSqlType);
          return null;
        });
  }

  @Override
  public void registerOutParameter(int parameterIndex, SQLType sqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterIndex, sqlType);
          return null;
        });
  }

  @Override
  public void registerOutParameter(int parameterIndex, SQLType sqlType, int scale)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterIndex, sqlType, scale);
          return null;
        });
  }

  @Override
  public void registerOutParameter(int parameterIndex, SQLType sqlType, String typeName)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterIndex, sqlType, typeName);
          return null;
        });
  }

  @Override
  public void registerOutParameter(String parameterName, SQLType sqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterName, sqlType);
          return null;
        });
  }

  @Override
  public void registerOutParameter(String parameterName, SQLType sqlType, int scale)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterName, sqlType, scale);
          return null;
        });
  }

  @Override
  public void registerOutParameter(String parameterName, SQLType sqlType, String typeName)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.registerOutParameter",
        () -> {
          this.statement.registerOutParameter(parameterName, sqlType, typeName);
          return null;
        });
  }

  // -------------------------

  @Override
  public ResultSet executeQuery() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeQuery",
        () -> this.statement.executeQuery());
  }

  @Override
  public int executeUpdate() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeUpdate",
        () -> this.statement.executeUpdate());
  }

  @Override
  public void setNull(int parameterIndex, int sqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNull",
        () -> {
          this.statement.setNull(parameterIndex, sqlType);
          return null;
        });
  }

  @Override
  public void setBoolean(int parameterIndex, boolean x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBoolean",
        () -> {
          this.statement.setBoolean(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setByte(int parameterIndex, byte x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setByte",
        () -> {
          this.statement.setByte(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setShort(int parameterIndex, short x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setShort",
        () -> {
          this.statement.setShort(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setInt(int parameterIndex, int x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setInt",
        () -> {
          this.statement.setInt(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setLong(int parameterIndex, long x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setLong",
        () -> {
          this.statement.setLong(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setFloat(int parameterIndex, float x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setFloat",
        () -> {
          this.statement.setFloat(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setDouble(int parameterIndex, double x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setDouble",
        () -> {
          this.statement.setDouble(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBigDecimal",
        () -> {
          this.statement.setBigDecimal(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setString(int parameterIndex, String x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setString",
        () -> {
          this.statement.setString(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setBytes(int parameterIndex, byte[] x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBytes",
        () -> {
          this.statement.setBytes(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setDate(int parameterIndex, Date x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setDate",
        () -> {
          this.statement.setDate(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setTime(int parameterIndex, Time x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTime",
        () -> {
          this.statement.setTime(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTimestamp",
        () -> {
          this.statement.setTimestamp(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setAsciiStream",
        () -> {
          this.statement.setAsciiStream(parameterIndex, x);
          return null;
        });
  }

  @Override
  @SuppressWarnings("deprecation")
  public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setUnicodeStream",
        () -> {
          this.statement.setUnicodeStream(parameterIndex, x, length);
          return null;
        });
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBinaryStream",
        () -> {
          this.statement.setBinaryStream(parameterIndex, x, length);
          return null;
        });
  }

  @Override
  public void clearParameters() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.clearParameters",
        () -> {
          this.statement.clearParameters();
          return null;
        });
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterIndex, x, targetSqlType);
          return null;
        });
  }

  @Override
  public void setObject(int parameterIndex, Object x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterIndex, x);
          return null;
        });
  }

  @Override
  public boolean execute() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.execute",
        () -> this.statement.execute());
  }

  @Override
  public void addBatch() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.addBatch",
        () -> {
          this.statement.addBatch();
          return null;
        });
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, int length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setCharacterStream",
        () -> {
          this.statement.setCharacterStream(parameterIndex, reader, length);
          return null;
        });
  }

  @Override
  public void setRef(int parameterIndex, Ref x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setRef",
        () -> {
          this.statement.setRef(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setBlob(int parameterIndex, Blob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBlob",
        () -> {
          this.statement.setBlob(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setClob(int parameterIndex, Clob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setClob",
        () -> {
          this.statement.setClob(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setArray(int parameterIndex, Array x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setArray",
        () -> {
          this.statement.setArray(parameterIndex, x);
          return null;
        });
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getMetaData",
        () -> this.statement.getMetaData());
  }

  @Override
  public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setDate",
        () -> {
          this.statement.setDate(parameterIndex, x, cal);
          return null;
        });
  }

  @Override
  public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTime",
        () -> {
          this.statement.setTime(parameterIndex, x, cal);
          return null;
        });
  }

  @Override
  public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setTimestamp",
        () -> {
          this.statement.setTimestamp(parameterIndex, x, cal);
          return null;
        });
  }

  @Override
  public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNull",
        () -> {
          this.statement.setNull(parameterIndex, sqlType, typeName);
          return null;
        });
  }

  @Override
  public void setURL(int parameterIndex, URL x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setURL",
        () -> {
          this.statement.setURL(parameterIndex, x);
          return null;
        });
  }

  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getParameterMetaData",
        () -> this.statement.getParameterMetaData());
  }

  @Override
  public void setRowId(int parameterIndex, RowId x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setRowId",
        () -> {
          this.statement.setRowId(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setNString(int parameterIndex, String value) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNString",
        () -> {
          this.statement.setNString(parameterIndex, value);
          return null;
        });
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNCharacterStream",
        () -> {
          this.statement.setNCharacterStream(parameterIndex, value, length);
          return null;
        });
  }

  @Override
  public void setNClob(int parameterIndex, NClob value) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNClob",
        () -> {
          this.statement.setNClob(parameterIndex, value);
          return null;
        });
  }

  @Override
  public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setClob",
        () -> {
          this.statement.setClob(parameterIndex, reader, length);
          return null;
        });
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBlob",
        () -> {
          this.statement.setBlob(parameterIndex, inputStream, length);
          return null;
        });
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNClob",
        () -> {
          this.statement.setNClob(parameterIndex, reader, length);
          return null;
        });
  }

  @Override
  public void setSQLXML(int parameterIndex, SQLXML xmlObject) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setSQLXML",
        () -> {
          this.statement.setSQLXML(parameterIndex, xmlObject);
          return null;
        });
  }

  @Override
  public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
          return null;
        });
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setAsciiStream",
        () -> {
          this.statement.setAsciiStream(parameterIndex, x, length);
          return null;
        });
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBinaryStream",
        () -> {
          this.statement.setBinaryStream(parameterIndex, x, length);
          return null;
        });
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setCharacterStream",
        () -> {
          this.statement.setCharacterStream(parameterIndex, reader, length);
          return null;
        });
  }

  @Override
  public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setAsciiStream",
        () -> {
          this.statement.setAsciiStream(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBinaryStream",
        () -> {
          this.statement.setBinaryStream(parameterIndex, x);
          return null;
        });
  }

  @Override
  public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setCharacterStream",
        () -> {
          this.statement.setCharacterStream(parameterIndex, reader);
          return null;
        });
  }

  @Override
  public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNCharacterStream",
        () -> {
          this.statement.setNCharacterStream(parameterIndex, value);
          return null;
        });
  }

  @Override
  public void setClob(int parameterIndex, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setClob",
        () -> {
          this.statement.setClob(parameterIndex, reader);
          return null;
        });
  }

  @Override
  public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setBlob",
        () -> {
          this.statement.setBlob(parameterIndex, inputStream);
          return null;
        });
  }

  @Override
  public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setNClob",
        () -> {
          this.statement.setNClob(parameterIndex, reader);
          return null;
        });
  }

  @Override
  public void setObject(int parameterIndex, Object x, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterIndex, x, targetSqlType, scaleOrLength);
          return null;
        });
  }

  @Override
  public void setObject(int parameterIndex, Object x, SQLType targetSqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setObject",
        () -> {
          this.statement.setObject(parameterIndex, x, targetSqlType);
          return null;
        });
  }

  @Override
  public long executeLargeUpdate() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeLargeUpdate",
        () -> this.statement.executeLargeUpdate());
  }

  @Override
  public ResultSet executeQuery(String sql) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeQuery",
        () -> this.statement.executeQuery(sql));
  }

  @Override
  public int executeUpdate(String sql) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeUpdate",
        () -> this.statement.executeUpdate(sql));
  }

  @Override
  public void close() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.close",
        () -> {
          this.statement.close();
          return null;
        });
  }

  @Override
  public int getMaxFieldSize() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getMaxFieldSize",
        () -> this.statement.getMaxFieldSize());
  }

  @Override
  public void setMaxFieldSize(int max) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setMaxFieldSize",
        () -> {
          this.statement.setMaxFieldSize(max);
          return null;
        });
  }

  @Override
  public int getMaxRows() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getMaxRows",
        () -> this.statement.getMaxRows());
  }

  @Override
  public void setMaxRows(int max) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setMaxRows",
        () -> {
          this.statement.setMaxRows(max);
          return null;
        });
  }

  @Override
  public void setEscapeProcessing(boolean enable) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setEscapeProcessing",
        () -> {
          this.statement.setEscapeProcessing(enable);
          return null;
        });
  }

  @Override
  public int getQueryTimeout() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getQueryTimeout",
        () -> this.statement.getQueryTimeout());
  }

  @Override
  public void setQueryTimeout(int seconds) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setQueryTimeout",
        () -> {
          this.statement.setQueryTimeout(seconds);
          return null;
        });
  }

  @Override
  public void cancel() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.cancel",
        () -> {
          this.statement.cancel();
          return null;
        });
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getWarnings",
        () -> this.statement.getWarnings());
  }

  @Override
  public void clearWarnings() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.clearWarnings",
        () -> {
          this.statement.clearWarnings();
          return null;
        });
  }

  @Override
  public void setCursorName(String name) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setCursorName",
        () -> {
          this.statement.setCursorName(name);
          return null;
        });
  }

  @Override
  public boolean execute(String sql) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.execute",
        () -> this.statement.execute(sql));
  }

  @Override
  public ResultSet getResultSet() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getResultSet",
        () -> this.statement.getResultSet());
  }

  @Override
  public int getUpdateCount() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getUpdateCount",
        () -> this.statement.getUpdateCount());
  }

  @Override
  public boolean getMoreResults() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getMoreResults",
        () -> this.statement.getMoreResults());
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getFetchDirection",
        () -> this.statement.getFetchDirection());
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setFetchDirection",
        () -> {
          this.statement.setFetchDirection(direction);
          return null;
        });
  }

  @Override
  public int getFetchSize() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getFetchSize",
        () -> this.statement.getFetchSize());
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setFetchSize",
        () -> {
          this.statement.setFetchSize(rows);
          return null;
        });
  }

  @Override
  public int getResultSetConcurrency() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getResultSetConcurrency",
        () -> this.statement.getResultSetConcurrency());
  }

  @Override
  public int getResultSetType() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getResultSetType",
        () -> this.statement.getResultSetType());
  }

  @Override
  public void addBatch(String sql) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.addBatch",
        () -> {
          this.statement.addBatch(sql);
          return null;
        });
  }

  @Override
  public void clearBatch() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.clearBatch",
        () -> {
          this.statement.clearBatch();
          return null;
        });
  }

  @Override
  public int[] executeBatch() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeBatch",
        () -> this.statement.executeBatch());
  }

  @Override
  public Connection getConnection() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getConnection",
        () -> this.statement.getConnection());
  }

  @Override
  public boolean getMoreResults(int current) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getMoreResults",
        () -> this.statement.getMoreResults(current));
  }

  @Override
  public ResultSet getGeneratedKeys() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getGeneratedKeys",
        () -> this.statement.getGeneratedKeys());
  }

  @Override
  public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeUpdate",
        () -> this.statement.executeUpdate(sql, autoGeneratedKeys));
  }

  @Override
  public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeUpdate",
        () -> this.statement.executeUpdate(sql, columnIndexes));
  }

  @Override
  public int executeUpdate(String sql, String[] columnNames) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.executeUpdate",
        () -> this.statement.executeUpdate(sql, columnNames));
  }

  @Override
  public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.execute",
        () -> this.statement.execute(sql, autoGeneratedKeys));
  }

  @Override
  public boolean execute(String sql, int[] columnIndexes) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.execute",
        () -> this.statement.execute(sql, columnIndexes));
  }

  @Override
  public boolean execute(String sql, String[] columnNames) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.execute",
        () -> this.statement.execute(sql, columnNames));
  }

  @Override
  public int getResultSetHoldability() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.getResultSetHoldability",
        () -> this.statement.getResultSetHoldability());
  }

  @Override
  public boolean isClosed() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.isClosed",
        () -> this.statement.isClosed());
  }

  @Override
  public boolean isPoolable() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.isPoolable",
        () -> this.statement.isPoolable());
  }

  @Override
  public void setPoolable(boolean poolable) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.setPoolable",
        () -> {
          this.statement.setPoolable(poolable);
          return null;
        });
  }

  @Override
  public void closeOnCompletion() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.closeOnCompletion",
        () -> {
          this.statement.closeOnCompletion();
          return null;
        });
  }

  @Override
  public boolean isCloseOnCompletion() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.statementClass,
        "CallableStatement.isCloseOnCompletion",
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
