/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.jdbc.PgResultSet;

import java.math.BigDecimal;
import java.sql.SQLException;


// TODO: Review for conversion compatibility with PgResultSet
//       Or best - shared implementation
// TODO: Consider renaming "PgInt8"
public class Int8ValueAccess extends BaseValueAccess {

  private final long value;

  public Int8ValueAccess(BaseConnection connection, long value) {
    super(connection);
    this.value = value;
  }

  /**
   * {@inheritDoc}
   *
   * @see Oid#INT8
   */
  @Override
  protected int getOid() {
    return Oid.INT8;
  }

  @Override
  public String getString() {
    return Long.toString(value);
  }

  /**
   * {@inheritDoc}
   *
   * @see  PgResultSet#getBoolean(int)
   */
  @Override
  public boolean getBoolean() throws SQLException {
    return BooleanTypeUtil.fromNumber(value);
  }

  @Override
  public byte getByte() {
    return (byte)value;
  }

  @Override
  public short getShort() {
    return (short)value;
  }

  @Override
  public int getInt() {
    return (int)value;
  }

  @Override
  public long getLong() {
    return value;
  }

  @Override
  public float getFloat() {
    return value;
  }

  @Override
  public double getDouble() {
    return value;
  }

  @Override
  public BigDecimal getBigDecimal() {
    return BigDecimal.valueOf(value);
  }

  @Override
  public boolean wasNull() {
    return false;
  }
}
