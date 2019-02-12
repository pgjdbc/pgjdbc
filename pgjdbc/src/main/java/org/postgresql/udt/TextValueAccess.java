/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.util.GT;
import org.postgresql.util.NumberConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
//#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
import java.time.LocalDateTime;
import java.time.LocalTime;
//#endif
import java.util.Calendar;


// TODO: Consider how to share this implementation with PgResultSet
// TODO: Consider renaming "PgText"
public class TextValueAccess extends BaseValueAccess {

  private final int oid;
  private final String value;

  public TextValueAccess(BaseConnection connection, int oid, String value) {
    super(connection);
    this.oid = oid;
    this.value = value;
  }

  @Override
  protected int getOid() {
    return oid;
  }

  @Override
  public String getString() {
    return value;
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getBoolean(int)
   */
  @Override
  public boolean getBoolean() throws SQLException {
    return (value == null) ? false : BooleanTypeUtil.fromString(value);
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getByte(int)
   */
  @Override
  public byte getByte() throws SQLException {
    return NumberConverter.toByte(NumberConverter.trimMoney(value));
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getShort(int)
   */
  @Override
  public short getShort() throws SQLException {
    return NumberConverter.toShort(value);
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getInt(int)
   */
  @Override
  public int getInt() throws SQLException {
    return NumberConverter.toInt(NumberConverter.trimMoney(value));
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getLong(int)
   */
  @Override
  public long getLong() throws SQLException {
    return NumberConverter.toLong(NumberConverter.trimMoney(value));
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getFloat(int)
   */
  @Override
  public float getFloat() throws SQLException {
    return NumberConverter.toFloat(NumberConverter.trimMoney(value));
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getDouble(int)
   */
  @Override
  public double getDouble() throws SQLException {
    return NumberConverter.toDouble(NumberConverter.trimMoney(value));
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getBigDecimal(int)
   */
  @Override
  public BigDecimal getBigDecimal() throws SQLException {
    return NumberConverter.toBigDecimal(NumberConverter.trimMoney(value));
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgConnection#encodeString(java.lang.String)
   */
  @Override
  public byte[] getBytes() throws SQLException {
    try {
      return connection.getEncoding().encode(value);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Unable to translate data into the desired encoding."),
          PSQLState.DATA_ERROR, ioe);
    }
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getDate(int, java.util.Calendar)
   */
  @Override
  public Date getDate(Calendar cal) throws SQLException {
    if (value == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    return connection.getTimestampUtils().toDate(cal, value);
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getTime(int, java.util.Calendar)
   */
  @Override
  public Time getTime(Calendar cal) throws SQLException {
    if (value == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    return connection.getTimestampUtils().toTime(cal, value);
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getTimestamp(int, java.util.Calendar)
   */
  @Override
  public Timestamp getTimestamp(Calendar cal) throws SQLException {
    if (value == null) {
      return null;
    }
    if (cal == null) {
      cal = getDefaultCalendar();
    }
    return connection.getTimestampUtils().toTimestamp(cal, value);
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getArray(int)
   */
  @Override
  public Array getArray() {
    return (value == null) ? null : new PgArray(connection, oid, value);
  }

  @Override
  public boolean wasNull() {
    return value == null;
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getLocalDateTime(int)
   */
  @Override
  public LocalDateTime getLocalDateTime() throws SQLException {
    return connection.getTimestampUtils().toLocalDateTime(value);
  }

  /**
   * {@inheritDoc}
   *
   * @see PgResultSet#getLocalTime(int)
   */
  @Override
  public LocalTime getLocalTime() throws SQLException {
    return connection.getTimestampUtils().toLocalTime(value);
  }
  //#endif
}
