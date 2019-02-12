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
// TODO: Consider renaming "PgFloat8"
public class Float8ValueAccess extends BaseValueAccess {

  private final double value;

  public Float8ValueAccess(BaseConnection connection, double value) {
    super(connection);
    this.value = value;
  }

  /**
   * {@inheritDoc}
   *
   * @see Oid#FLOAT8
   */
  @Override
  protected int getOid() {
    return Oid.FLOAT8;
  }

  @Override
  public String getString() {
    return Double.toString(value);
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
    return (long)value;
  }

  @Override
  public float getFloat() {
    return (float)value;
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
