/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.udt.UdtMap;
import org.postgresql.udt.ValueAccess;
import org.postgresql.udt.ValueAccessHelper;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLState;

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
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
import java.time.LocalTime;
//#endif
import java.util.Calendar;


/**
 * Implementation of {@link ValueAccess} that retrieves the value from the given
 * column of a {@link PgResultSet}.
 */
class PgResultSetValueAccess implements ValueAccess {

  private final PgResultSet result;
  private final int columnIndex;

  PgResultSetValueAccess(PgResultSet result, int columnIndex) {
    this.result = result;
    this.columnIndex = columnIndex;
  }

  @Override
  public String getString() throws SQLException {
    return result.getString(columnIndex);
  }

  @Override
  public boolean getBoolean() throws SQLException {
    return result.getBoolean(columnIndex);
  }

  @Override
  public byte getByte() throws SQLException {
    return result.getByte(columnIndex);
  }

  @Override
  public short getShort() throws SQLException {
    return result.getShort(columnIndex);
  }

  @Override
  public int getInt() throws SQLException {
    return result.getInt(columnIndex);
  }

  @Override
  public long getLong() throws SQLException {
    return result.getLong(columnIndex);
  }

  @Override
  public float getFloat() throws SQLException {
    return result.getFloat(columnIndex);
  }

  @Override
  public double getDouble() throws SQLException {
    return result.getDouble(columnIndex);
  }

  @Override
  public BigDecimal getBigDecimal() throws SQLException {
    return result.getBigDecimal(columnIndex);
  }

  @Override
  public byte[] getBytes() throws SQLException {
    return result.getBytes(columnIndex);
  }

  @Override
  public Date getDate() throws SQLException {
    return result.getDate(columnIndex);
  }

  @Override
  public Time getTime() throws SQLException {
    return result.getTime(columnIndex);
  }

  @Override
  public Timestamp getTimestamp() throws SQLException {
    return result.getTimestamp(columnIndex);
  }

  @Override
  public Reader getCharacterStream() throws SQLException {
    return result.getCharacterStream(columnIndex);
  }

  @Override
  public InputStream getAsciiStream() throws SQLException {
    return result.getAsciiStream(columnIndex);
  }

  @Override
  public InputStream getBinaryStream() throws SQLException {
    return result.getBinaryStream(columnIndex);
  }

  @Override
  public Object getObject(UdtMap udtMap) throws SQLException {
    // TODO: How does udtMap pass-through to the result?
    return result.getObject(columnIndex);
  }

  @Override
  public Ref getRef() throws SQLException {
    return result.getRef(columnIndex);
  }

  @Override
  public Blob getBlob() throws SQLException {
    return result.getBlob(columnIndex);
  }

  @Override
  public Clob getClob() throws SQLException {
    return result.getClob(columnIndex);
  }

  @Override
  public Array getArray() throws SQLException {
    return result.getArray(columnIndex);
  }

  @Override
  public boolean wasNull() throws SQLException {
    return result.wasNull();
  }

  @Override
  public URL getURL() throws SQLException {
    return result.getURL(columnIndex);
  }

  @Override
  public NClob getNClob() throws SQLException {
    return result.getNClob(columnIndex);
  }

  @Override
  public String getNString() throws SQLException {
    return result.getNString(columnIndex);
  }

  @Override
  public SQLXML getSQLXML() throws SQLException {
    return result.getSQLXML(columnIndex);
  }

  @Override
  public RowId getRowId() throws SQLException {
    return result.getRowId(columnIndex);
  }

  @Override
  public <T extends PGobject> T getPGobject(Class<T> type) throws SQLException {
    return result.getPGobject(columnIndex, type);
  }

  /* TODO: Required?
  @Override
  public <T> T getObjectCustomType(UdtMap udtMap, String type, Class<? extends T> customType) throws SQLException {
    return result.getObjectCustomType(columnIndex, udtMap, type, customType);
  }
   */

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  public LocalDateTime getLocalDateTime() throws SQLException {
    return result.getLocalDateTime(columnIndex);
  }

  @Override
  public LocalTime getLocalTime() throws SQLException {
    return result.getLocalTime(columnIndex);
  }
  //#endif

  @Override
  public Calendar getDefaultCalendar() {
    return result.getDefaultCalendar();
  }

  @Override
  public <T> T getObject(Class<T> type, UdtMap udtMap) throws SQLException {
    return ValueAccessHelper.getObject(this, result.getSQLType(columnIndex),
        result.getPGType(columnIndex), type, udtMap,
        // PSQLState.INVALID_PARAMETER_VALUE is consistent with previous implementation of PgResultSet.getObject(Class)
        PSQLState.INVALID_PARAMETER_VALUE);
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSetSQLInput
   */
  @Override
  public PgResultSetSQLInput getSQLInput(UdtMap udtMap) {
    return new PgResultSetSQLInput(result, columnIndex, udtMap);
  }
}
