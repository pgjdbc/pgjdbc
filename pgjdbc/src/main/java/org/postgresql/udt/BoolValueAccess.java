/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgResultSet;

import java.math.BigDecimal;
import java.sql.SQLException;


// TODO: Review for conversion compatibility with PgResultSet
//       Or best - shared implementation
public class BoolValueAccess extends BaseValueAccess {

  private final boolean value;

  public BoolValueAccess(BaseConnection connection, boolean value) {
    super(connection);
    this.value = value;
  }

  /**
   * {@inheritDoc}
   *
   * @see Oid#BOOL
   */
  @Override
  protected int getOid() {
    return Oid.BOOL;
  }

  @Override
  public String getString() throws SQLException {
    // TODO: What is the correct value?  t/f or true/false?
    return Boolean.toString(value);
  }

  @Override
  public boolean getBoolean() throws SQLException {
    return value;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Performs the inverse of the conversion from 0 -&gt; false and 1 -&gt; true
   * inside of {@link PgResultSet#getBoolean(int)}, despite not being able to
   * currently find whether this conversion is performed inside {@link PgResultSet#getByte(int)}.
   * </p>
   *
   * @see PgResultSet#getBoolean(int)
   * @see PgResultSet#getByte(int)
   */
  @Override
  public byte getByte() throws SQLException {
    return value ? (byte)1 : (byte)0;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Performs the inverse of the conversion from 0 -&gt; false and 1 -&gt; true
   * inside of {@link PgResultSet#getBoolean(int)}, despite not being able to
   * currently find whether this conversion is performed inside {@link PgResultSet#getShort(int)}.
   * </p>
   *
   * @see PgResultSet#getBoolean(int)
   * @see PgResultSet#getShort(int)
   */
  @Override
  public short getShort() throws SQLException {
    return value ? (short)1 : (short)0;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Performs the inverse of the conversion from 0 -&gt; false and 1 -&gt; true
   * inside of {@link PgResultSet#getBoolean(int)}, despite not being able to
   * currently find whether this conversion is performed inside {@link PgResultSet#getInt(int)}.
   * </p>
   *
   * @see PgResultSet#getBoolean(int)
   * @see PgResultSet#getInt(int)
   */
  @Override
  public int getInt() throws SQLException {
    return value ? 1 : 0;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Performs the inverse of the conversion from 0 -&gt; false and 1 -&gt; true
   * inside of {@link PgResultSet#getBoolean(int)}, despite not being able to
   * currently find whether this conversion is performed inside {@link PgResultSet#getLong(int)}.
   * </p>
   *
   * @see PgResultSet#getBoolean(int)
   * @see PgResultSet#getLong(int)
   */
  @Override
  public long getLong() throws SQLException {
    return value ? 1 : 0;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Performs the inverse of the conversion from 0 -&gt; false and 1 -&gt; true
   * inside of {@link PgResultSet#getBoolean(int)}, despite not being able to
   * currently find whether this conversion is performed inside {@link PgResultSet#getFloat(int)}.
   * </p>
   *
   * @see PgResultSet#getBoolean(int)
   * @see PgResultSet#getFloat(int)
   */
  @Override
  public float getFloat() throws SQLException {
    return value ? 1 : 0;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Performs the inverse of the conversion from 0 -&gt; false and 1 -&gt; true
   * inside of {@link PgResultSet#getBoolean(int)}, despite not being able to
   * currently find whether this conversion is performed inside {@link PgResultSet#getDouble(int)}.
   * </p>
   *
   * @see PgResultSet#getBoolean(int)
   * @see PgResultSet#getDouble(int)
   */
  @Override
  public double getDouble() throws SQLException {
    return value ? 1 : 0;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Performs the inverse of the conversion from 0 -&gt; false and 1 -&gt; true
   * inside of {@link PgResultSet#getBoolean(int)}, despite not being able to
   * currently find whether this conversion is performed inside {@link PgResultSet#getBigDecimal(int)}.
   * </p>
   *
   * @see PgResultSet#getBoolean(int)
   * @see PgResultSet#getBigDecimal(int)
   */
  @Override
  public BigDecimal getBigDecimal() throws SQLException {
    return value ? BigDecimal.ONE : BigDecimal.ZERO;
  }

  @Override
  public Object getObject(UdtMap udtMap) throws SQLException {
    // TODO: typemap?
    return value;
  }
}
