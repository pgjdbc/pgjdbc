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
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

public class ResultSetWrapper implements ResultSet {

  protected ResultSet resultSet;
  protected Class<?> resultSetClass;
  protected ConnectionPluginManager pluginManager;

  public ResultSetWrapper(ResultSet resultSet, ConnectionPluginManager pluginManager) {
    if (resultSet == null) {
      throw new IllegalArgumentException("resultSet");
    }
    if (pluginManager == null) {
      throw new IllegalArgumentException("pluginManager");
    }

    this.resultSet = resultSet;
    this.resultSetClass = this.resultSet.getClass();
    this.pluginManager = pluginManager;
  }

  @Override
  public boolean next() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.next",
        () -> this.resultSet.next());
  }

  @Override
  public void close() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.close",
        () -> {
          this.resultSet.close();
          return null;
        });
  }

  @Override
  public boolean wasNull() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.wasNull",
        () -> this.resultSet.wasNull());
  }

  @Override
  public String getString(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getString",
        () -> this.resultSet.getString(columnIndex));
  }

  @Override
  public boolean getBoolean(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBoolean",
        () -> this.resultSet.getBoolean(columnIndex));
  }

  @Override
  public byte getByte(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getByte",
        () -> this.resultSet.getByte(columnIndex));
  }

  @Override
  public short getShort(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getShort",
        () -> this.resultSet.getShort(columnIndex));
  }

  @Override
  public int getInt(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getInt",
        () -> this.resultSet.getInt(columnIndex));
  }

  @Override
  public long getLong(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getLong",
        () -> this.resultSet.getLong(columnIndex));
  }

  @Override
  public float getFloat(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getFloat",
        () -> this.resultSet.getFloat(columnIndex));
  }

  @Override
  public double getDouble(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getDouble",
        () -> this.resultSet.getDouble(columnIndex));
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBigDecimal",
        () -> this.resultSet.getBigDecimal(columnIndex, scale));
  }

  @Override
  public byte[] getBytes(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBytes",
        () -> this.resultSet.getBytes(columnIndex));
  }

  @Override
  public Date getDate(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getDate",
        () -> this.resultSet.getDate(columnIndex));
  }

  @Override
  public Time getTime(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTime",
        () -> this.resultSet.getTime(columnIndex));
  }

  @Override
  public Timestamp getTimestamp(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTimestamp",
        () -> this.resultSet.getTimestamp(columnIndex));
  }

  @Override
  public InputStream getAsciiStream(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getAsciiStream",
        () -> this.resultSet.getAsciiStream(columnIndex));
  }

  @Override
  public InputStream getUnicodeStream(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getUnicodeStream",
        () -> this.resultSet.getUnicodeStream(columnIndex));
  }

  @Override
  public InputStream getBinaryStream(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBinaryStream",
        () -> this.resultSet.getBinaryStream(columnIndex));
  }

  @Override
  public String getString(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getString",
        () -> this.resultSet.getString(columnLabel));
  }

  @Override
  public boolean getBoolean(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBoolean",
        () -> this.resultSet.getBoolean(columnLabel));
  }

  @Override
  public byte getByte(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getByte",
        () -> this.resultSet.getByte(columnLabel));
  }

  @Override
  public short getShort(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getShort",
        () -> this.resultSet.getShort(columnLabel));
  }

  @Override
  public int getInt(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getInt",
        () -> this.resultSet.getInt(columnLabel));
  }

  @Override
  public long getLong(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getLong",
        () -> this.resultSet.getLong(columnLabel));
  }

  @Override
  public float getFloat(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getFloat",
        () -> this.resultSet.getFloat(columnLabel));
  }

  @Override
  public double getDouble(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getDouble",
        () -> this.resultSet.getDouble(columnLabel));
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBigDecimal",
        () -> this.resultSet.getBigDecimal(columnLabel, scale));
  }

  @Override
  public byte[] getBytes(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBytes",
        () -> this.resultSet.getBytes(columnLabel));
  }

  @Override
  public Date getDate(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getDate",
        () -> this.resultSet.getDate(columnLabel));
  }

  @Override
  public Time getTime(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTime",
        () -> this.resultSet.getTime(columnLabel));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTimestamp",
        () -> this.resultSet.getTimestamp(columnLabel));
  }

  @Override
  public InputStream getAsciiStream(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getAsciiStream",
        () -> this.resultSet.getAsciiStream(columnLabel));
  }

  @Override
  public InputStream getUnicodeStream(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getUnicodeStream",
        () -> this.resultSet.getUnicodeStream(columnLabel));
  }

  @Override
  public InputStream getBinaryStream(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBinaryStream",
        () -> this.resultSet.getBinaryStream(columnLabel));
  }

  @Override
  public SQLWarning getWarnings() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getWarnings",
        () -> this.resultSet.getWarnings());
  }

  @Override
  public void clearWarnings() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.clearWarnings",
        () -> {
          this.resultSet.clearWarnings();
          return null;
        });
  }

  @Override
  public String getCursorName() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getCursorName",
        () -> this.resultSet.getCursorName());
  }

  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getMetaData",
        () -> this.resultSet.getMetaData());
  }

  @Override
  public Object getObject(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getObject",
        () -> this.resultSet.getObject(columnIndex));
  }

  @Override
  public Object getObject(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getObject",
        () -> this.resultSet.getObject(columnLabel));
  }

  @Override
  public int findColumn(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.findColumn",
        () -> this.resultSet.findColumn(columnLabel));
  }

  @Override
  public Reader getCharacterStream(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getCharacterStream",
        () -> this.resultSet.getCharacterStream(columnIndex));
  }

  @Override
  public Reader getCharacterStream(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getCharacterStream",
        () -> this.resultSet.getCharacterStream(columnLabel));
  }

  @Override
  public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBigDecimal",
        () -> this.resultSet.getBigDecimal(columnIndex));
  }

  @Override
  public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBigDecimal",
        () -> this.resultSet.getBigDecimal(columnLabel));
  }

  @Override
  public boolean isBeforeFirst() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.isBeforeFirst",
        () -> this.resultSet.isBeforeFirst());
  }

  @Override
  public boolean isAfterLast() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.isAfterLast",
        () -> this.resultSet.isAfterLast());
  }

  @Override
  public boolean isFirst() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.isFirst",
        () -> this.resultSet.isFirst());
  }

  @Override
  public boolean isLast() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.isLast",
        () -> this.resultSet.isLast());
  }

  @Override
  public void beforeFirst() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.beforeFirst",
        () -> {
          this.resultSet.beforeFirst();
          return null;
        });
  }

  @Override
  public void afterLast() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.afterLast",
        () -> {
          this.resultSet.afterLast();
          return null;
        });
  }

  @Override
  public boolean first() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.first",
        () -> this.resultSet.first());
  }

  @Override
  public boolean last() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.last",
        () -> this.resultSet.last());
  }

  @Override
  public int getRow() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getRow",
        () -> this.resultSet.getRow());
  }

  @Override
  public boolean absolute(int row) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.absolute",
        () -> this.resultSet.absolute(row));
  }

  @Override
  public boolean relative(int rows) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.relative",
        () -> this.resultSet.relative(rows));
  }

  @Override
  public boolean previous() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.previous",
        () -> this.resultSet.previous());
  }

  @Override
  public int getFetchDirection() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getFetchDirection",
        () -> this.resultSet.getFetchDirection());
  }

  @Override
  public void setFetchDirection(int direction) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.setFetchDirection",
        () -> {
          this.resultSet.setFetchDirection(direction);
          return null;
        });
  }

  @Override
  public int getFetchSize() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getFetchSize",
        () -> this.resultSet.getFetchSize());
  }

  @Override
  public void setFetchSize(int rows) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.setFetchSize",
        () -> {
          this.resultSet.setFetchSize(rows);
          return null;
        });
  }

  @Override
  public int getType() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getType",
        () -> this.resultSet.getType());
  }

  @Override
  public int getConcurrency() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getConcurrency",
        () -> this.resultSet.getConcurrency());
  }

  @Override
  public boolean rowUpdated() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.rowUpdated",
        () -> this.resultSet.rowUpdated());
  }

  @Override
  public boolean rowInserted() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.rowInserted",
        () -> this.resultSet.rowInserted());
  }

  @Override
  public boolean rowDeleted() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.rowDeleted",
        () -> this.resultSet.rowDeleted());
  }

  @Override
  public void updateNull(int columnIndex) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNull",
        () -> {
          this.resultSet.updateNull(columnIndex);
          return null;
        });
  }

  @Override
  public void updateBoolean(int columnIndex, boolean x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBoolean",
        () -> {
          this.resultSet.updateBoolean(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateByte(int columnIndex, byte x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateByte",
        () -> {
          this.resultSet.updateByte(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateShort(int columnIndex, short x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateShort",
        () -> {
          this.resultSet.updateShort(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateInt(int columnIndex, int x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateInt",
        () -> {
          this.resultSet.updateInt(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateLong(int columnIndex, long x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateLong",
        () -> {
          this.resultSet.updateLong(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateFloat(int columnIndex, float x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateFloat",
        () -> {
          this.resultSet.updateFloat(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateDouble(int columnIndex, double x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateDouble",
        () -> {
          this.resultSet.updateDouble(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBigDecimal",
        () -> {
          this.resultSet.updateBigDecimal(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateString(int columnIndex, String x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateString",
        () -> {
          this.resultSet.updateString(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateBytes(int columnIndex, byte[] x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBytes",
        () -> {
          this.resultSet.updateBytes(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateDate(int columnIndex, Date x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateDate",
        () -> {
          this.resultSet.updateDate(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateTime(int columnIndex, Time x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateTime",
        () -> {
          this.resultSet.updateTime(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateTimestamp",
        () -> {
          this.resultSet.updateTimestamp(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateAsciiStream",
        () -> {
          this.resultSet.updateAsciiStream(columnIndex, x, length);
          return null;
        });
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBinaryStream",
        () -> {
          this.resultSet.updateBinaryStream(columnIndex, x, length);
          return null;
        });
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateCharacterStream",
        () -> {
          this.resultSet.updateCharacterStream(columnIndex, x, length);
          return null;
        });
  }

  @Override
  public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnIndex, x, scaleOrLength);
          return null;
        });
  }

  @Override
  public void updateObject(int columnIndex, Object x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateNull(String columnLabel) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNull",
        () -> {
          this.resultSet.updateNull(columnLabel);
          return null;
        });
  }

  @Override
  public void updateBoolean(String columnLabel, boolean x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBoolean",
        () -> {
          this.resultSet.updateBoolean(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateByte(String columnLabel, byte x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateByte",
        () -> {
          this.resultSet.updateByte(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateShort(String columnLabel, short x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateShort",
        () -> {
          this.resultSet.updateShort(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateInt(String columnLabel, int x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateInt",
        () -> {
          this.resultSet.updateInt(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateLong(String columnLabel, long x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateLong",
        () -> {
          this.resultSet.updateLong(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateFloat(String columnLabel, float x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateFloat",
        () -> {
          this.resultSet.updateFloat(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateDouble(String columnLabel, double x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateDouble",
        () -> {
          this.resultSet.updateDouble(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBigDecimal",
        () -> {
          this.resultSet.updateBigDecimal(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateString(String columnLabel, String x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateString",
        () -> {
          this.resultSet.updateString(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateBytes(String columnLabel, byte[] x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBytes",
        () -> {
          this.resultSet.updateBytes(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateDate(String columnLabel, Date x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateDate",
        () -> {
          this.resultSet.updateDate(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateTime(String columnLabel, Time x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateTime",
        () -> {
          this.resultSet.updateTime(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateTimestamp",
        () -> {
          this.resultSet.updateTimestamp(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateAsciiStream",
        () -> {
          this.resultSet.updateAsciiStream(columnLabel, x, length);
          return null;
        });
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, int length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBinaryStream",
        () -> {
          this.resultSet.updateBinaryStream(columnLabel, x, length);
          return null;
        });
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, int length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateCharacterStream",
        () -> {
          this.resultSet.updateCharacterStream(columnLabel, reader, length);
          return null;
        });
  }

  @Override
  public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnLabel, x, scaleOrLength);
          return null;
        });
  }

  @Override
  public void updateObject(String columnLabel, Object x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnLabel, x);
          return null;
        });
  }

  @Override
  public void insertRow() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.insertRow",
        () -> {
          this.resultSet.insertRow();
          return null;
        });
  }

  @Override
  public void updateRow() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateRow",
        () -> {
          this.resultSet.updateRow();
          return null;
        });
  }

  @Override
  public void deleteRow() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.deleteRow",
        () -> {
          this.resultSet.deleteRow();
          return null;
        });
  }

  @Override
  public void refreshRow() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.refreshRow",
        () -> {
          this.resultSet.refreshRow();
          return null;
        });
  }

  @Override
  public void cancelRowUpdates() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.cancelRowUpdates",
        () -> {
          this.resultSet.cancelRowUpdates();
          return null;
        });
  }

  @Override
  public void moveToInsertRow() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.moveToInsertRow",
        () -> {
          this.resultSet.moveToInsertRow();
          return null;
        });
  }

  @Override
  public void moveToCurrentRow() throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.moveToCurrentRow",
        () -> {
          this.resultSet.moveToCurrentRow();
          return null;
        });
  }

  @Override
  public Statement getStatement() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getStatement",
        () -> this.resultSet.getStatement());
  }

  @Override
  public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getObject",
        () -> this.resultSet.getObject(columnIndex, map));
  }

  @Override
  public Ref getRef(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getRef",
        () -> this.resultSet.getRef(columnIndex));
  }

  @Override
  public Blob getBlob(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBlob",
        () -> this.resultSet.getBlob(columnIndex));
  }

  @Override
  public Clob getClob(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getClob",
        () -> this.resultSet.getClob(columnIndex));
  }

  @Override
  public Array getArray(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getArray",
        () -> this.resultSet.getArray(columnIndex));
  }

  @Override
  public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getObject",
        () -> this.resultSet.getObject(columnLabel, map));
  }

  @Override
  public Ref getRef(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getRef",
        () -> this.resultSet.getRef(columnLabel));
  }

  @Override
  public Blob getBlob(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getBlob",
        () -> this.resultSet.getBlob(columnLabel));
  }

  @Override
  public Clob getClob(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getClob",
        () -> this.resultSet.getClob(columnLabel));
  }

  @Override
  public Array getArray(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getArray",
        () -> this.resultSet.getArray(columnLabel));
  }

  @Override
  public Date getDate(int columnIndex, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getDate",
        () -> this.resultSet.getDate(columnIndex, cal));
  }

  @Override
  public Date getDate(String columnLabel, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getDate",
        () -> this.resultSet.getDate(columnLabel, cal));
  }

  @Override
  public Time getTime(int columnIndex, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTime",
        () -> this.resultSet.getTime(columnIndex, cal));
  }

  @Override
  public Time getTime(String columnLabel, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTime",
        () -> this.resultSet.getTime(columnLabel, cal));
  }

  @Override
  public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTimestamp",
        () -> this.resultSet.getTimestamp(columnIndex, cal));
  }

  @Override
  public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getTimestamp",
        () -> this.resultSet.getTimestamp(columnLabel, cal));
  }

  @Override
  public URL getURL(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getURL",
        () -> this.resultSet.getURL(columnIndex));
  }

  @Override
  public URL getURL(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getURL",
        () -> this.resultSet.getURL(columnLabel));
  }

  @Override
  public void updateRef(int columnIndex, Ref x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateRef",
        () -> {
          this.resultSet.updateRef(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateRef(String columnLabel, Ref x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateRef",
        () -> {
          this.resultSet.updateRef(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateBlob(int columnIndex, Blob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBlob",
        () -> {
          this.resultSet.updateBlob(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateBlob(String columnLabel, Blob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBlob",
        () -> {
          this.resultSet.updateBlob(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateClob(int columnIndex, Clob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateClob",
        () -> {
          this.resultSet.updateClob(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateClob(String columnLabel, Clob x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateClob",
        () -> {
          this.resultSet.updateClob(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateArray(int columnIndex, Array x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateArray",
        () -> {
          this.resultSet.updateArray(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateArray(String columnLabel, Array x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateArray",
        () -> {
          this.resultSet.updateArray(columnLabel, x);
          return null;
        });
  }

  @Override
  public RowId getRowId(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getRowId",
        () -> this.resultSet.getRowId(columnIndex));
  }

  @Override
  public RowId getRowId(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getRowId",
        () -> this.resultSet.getRowId(columnLabel));
  }

  @Override
  public void updateRowId(int columnIndex, RowId x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateRowId",
        () -> {
          this.resultSet.updateRowId(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateRowId(String columnLabel, RowId x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateRowId",
        () -> {
          this.resultSet.updateRowId(columnLabel, x);
          return null;
        });
  }

  @Override
  public int getHoldability() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getHoldability",
        () -> this.resultSet.getHoldability());
  }

  @Override
  public boolean isClosed() throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.isClosed",
        () -> this.resultSet.isClosed());
  }

  @Override
  public void updateNString(int columnIndex, String nString) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNString",
        () -> {
          this.resultSet.updateNString(columnIndex, nString);
          return null;
        });
  }

  @Override
  public void updateNString(String columnLabel, String nString) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNString",
        () -> {
          this.resultSet.updateNString(columnLabel, nString);
          return null;
        });
  }

  @Override
  public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNClob",
        () -> {
          this.resultSet.updateNClob(columnIndex, nClob);
          return null;
        });
  }

  @Override
  public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNClob",
        () -> {
          this.resultSet.updateNClob(columnLabel, nClob);
          return null;
        });
  }

  @Override
  public NClob getNClob(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getNClob",
        () -> this.resultSet.getNClob(columnIndex));
  }

  @Override
  public NClob getNClob(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getNClob",
        () -> this.resultSet.getNClob(columnLabel));
  }

  @Override
  public SQLXML getSQLXML(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getSQLXML",
        () -> this.resultSet.getSQLXML(columnIndex));
  }

  @Override
  public SQLXML getSQLXML(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getSQLXML",
        () -> this.resultSet.getSQLXML(columnLabel));
  }

  @Override
  public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateSQLXML",
        () -> {
          this.resultSet.updateSQLXML(columnIndex, xmlObject);
          return null;
        });
  }

  @Override
  public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateSQLXML",
        () -> {
          this.resultSet.updateSQLXML(columnLabel, xmlObject);
          return null;
        });
  }

  @Override
  public String getNString(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getNString",
        () -> this.resultSet.getNString(columnIndex));
  }

  @Override
  public String getNString(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getNString",
        () -> this.resultSet.getNString(columnLabel));
  }

  @Override
  public Reader getNCharacterStream(int columnIndex) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getNCharacterStream",
        () -> this.resultSet.getNCharacterStream(columnIndex));
  }

  @Override
  public Reader getNCharacterStream(String columnLabel) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getNCharacterStream",
        () -> this.resultSet.getNCharacterStream(columnLabel));
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNCharacterStream",
        () -> {
          this.resultSet.updateNCharacterStream(columnIndex, x, length);
          return null;
        });
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNCharacterStream",
        () -> {
          this.resultSet.updateNCharacterStream(columnLabel, reader, length);
          return null;
        });
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateAsciiStream",
        () -> {
          this.resultSet.updateAsciiStream(columnIndex, x, length);
          return null;
        });
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBinaryStream",
        () -> {
          this.resultSet.updateBinaryStream(columnIndex, x, length);
          return null;
        });
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateCharacterStream",
        () -> {
          this.resultSet.updateCharacterStream(columnIndex, x, length);
          return null;
        });
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateAsciiStream",
        () -> {
          this.resultSet.updateAsciiStream(columnLabel, x, length);
          return null;
        });
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBinaryStream",
        () -> {
          this.resultSet.updateBinaryStream(columnLabel, x, length);
          return null;
        });
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateCharacterStream",
        () -> {
          this.resultSet.updateCharacterStream(columnLabel, reader, length);
          return null;
        });
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBlob",
        () -> {
          this.resultSet.updateBlob(columnIndex, inputStream, length);
          return null;
        });
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream, long length)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBlob",
        () -> {
          this.resultSet.updateBlob(columnLabel, inputStream, length);
          return null;
        });
  }

  @Override
  public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateClob",
        () -> {
          this.resultSet.updateClob(columnIndex, reader, length);
          return null;
        });
  }

  @Override
  public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateClob",
        () -> {
          this.resultSet.updateClob(columnLabel, reader, length);
          return null;
        });
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNClob",
        () -> {
          this.resultSet.updateNClob(columnIndex, reader, length);
          return null;
        });
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNClob",
        () -> {
          this.resultSet.updateNClob(columnLabel, reader, length);
          return null;
        });
  }

  @Override
  public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNCharacterStream",
        () -> {
          this.resultSet.updateNCharacterStream(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNCharacterStream",
        () -> {
          this.resultSet.updateNCharacterStream(columnLabel, reader);
          return null;
        });
  }

  @Override
  public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateAsciiStream",
        () -> {
          this.resultSet.updateAsciiStream(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBinaryStream",
        () -> {
          this.resultSet.updateBinaryStream(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateCharacterStream",
        () -> {
          this.resultSet.updateCharacterStream(columnIndex, x);
          return null;
        });
  }

  @Override
  public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateAsciiStream",
        () -> {
          this.resultSet.updateAsciiStream(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBinaryStream",
        () -> {
          this.resultSet.updateBinaryStream(columnLabel, x);
          return null;
        });
  }

  @Override
  public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateCharacterStream",
        () -> {
          this.resultSet.updateCharacterStream(columnLabel, reader);
          return null;
        });
  }

  @Override
  public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBlob",
        () -> {
          this.resultSet.updateBlob(columnIndex, inputStream);
          return null;
        });
  }

  @Override
  public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateBlob",
        () -> {
          this.resultSet.updateBlob(columnLabel, inputStream);
          return null;
        });
  }

  @Override
  public void updateClob(int columnIndex, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateClob",
        () -> {
          this.resultSet.updateClob(columnIndex, reader);
          return null;
        });
  }

  @Override
  public void updateClob(String columnLabel, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateClob",
        () -> {
          this.resultSet.updateClob(columnLabel, reader);
          return null;
        });
  }

  @Override
  public void updateNClob(int columnIndex, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNClob",
        () -> {
          this.resultSet.updateNClob(columnIndex, reader);
          return null;
        });
  }

  @Override
  public void updateNClob(String columnLabel, Reader reader) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateNClob",
        () -> {
          this.resultSet.updateNClob(columnLabel, reader);
          return null;
        });
  }

  @Override
  public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getObject",
        () -> this.resultSet.getObject(columnIndex, type));
  }

  @Override
  public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
    return WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.getObject",
        () -> this.resultSet.getObject(columnLabel, type));
  }

  @Override
  public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnIndex, x, targetSqlType, scaleOrLength);
          return null;
        });
  }

  @Override
  public void updateObject(String columnLabel, Object x, SQLType targetSqlType,
      int scaleOrLength) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnLabel, x, targetSqlType, scaleOrLength);
          return null;
        });
  }

  @Override
  public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnIndex, x, targetSqlType);
          return null;
        });
  }

  @Override
  public void updateObject(String columnLabel, Object x, SQLType targetSqlType)
      throws SQLException {
    WrapperUtils.executeWithPlugins(this.pluginManager,
        this.resultSetClass,
        "ResultSet.updateObject",
        () -> {
          this.resultSet.updateObject(columnLabel, x, targetSqlType);
          return null;
        });
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    return this.resultSet.unwrap(iface);
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return this.resultSet.isWrapperFor(iface);
  }

  @Override
  public boolean equals(Object obj) {
    return this.resultSet.equals(obj);
  }

  @Override
  public int hashCode() {
    return this.resultSet.hashCode();
  }

}
