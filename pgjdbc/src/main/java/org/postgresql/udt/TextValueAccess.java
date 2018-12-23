/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;


// TODO: Review for conversion compatibility with PgResultSet
//       Or best - shared implementation
public class TextValueAccess extends BaseValueAccess {

  private final int oid;
  private final String value;

  public TextValueAccess(BaseConnection connection, int oid, String value, UdtMap udtMap) {
    super(connection, udtMap);
    this.oid = oid;
    this.value = value;
  }

  @Override
  protected int getOid() {
    return oid;
  }

  @Override
  public String getString() throws SQLException {
    return value;
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getBoolean(int)
   */
  @Override
  public boolean getBoolean() throws SQLException {
    return BooleanTypeUtil.fromString(value);
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getBoolean(int)
   */
  @Override
  public byte getByte() throws SQLException {
    try {
      return Byte.parseByte(value.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
  }

  @Override
  public short getShort() throws SQLException {
    return PgResultSet.toShort(value);
  }

  @Override
  public int getInt() throws SQLException {
    return PgResultSet.toInt(value);
  }

  @Override
  public long getLong() throws SQLException {
    return PgResultSet.toLong(value);
  }

  @Override
  public float getFloat() throws SQLException {
    return PgResultSet.toFloat(value);
  }

  @Override
  public double getDouble() throws SQLException {
    return PgResultSet.toDouble(value);
  }

  @Override
  public BigDecimal getBigDecimal() throws SQLException {
    return PgResultSet.toBigDecimal(value);
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

  @Override
  public Date getDate() throws SQLException {
    return connection.getTimestampUtils().toDate(null, value);
  }

  @Override
  public Time getTime() throws SQLException {
    return connection.getTimestampUtils().toTime(null, value);
  }

  @Override
  public Timestamp getTimestamp() throws SQLException {
    return connection.getTimestampUtils().toTimestamp(null, value);
  }

  @Override
  public Object getObject() throws SQLException {
    // TODO: typemap?  Could probably do a lot more in AbstractValueAccess for getObject()
    //       regarding intercepting custom type maps and doing conversions from the various get* methods.
    //       Then this would become a protected getObjectImpl() used when no custom maps applied.
    return value;
  }

  // TODO: getArray()

  @Override
  public boolean wasNull() throws SQLException {
    return value == null;
  }

  // TODO: getPGobject(Class)? with ArrayAssistantRegistry?

  // TODO: getObjectCustomType(...)? with ArrayAssistantRegistry?
}
