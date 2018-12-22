/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Map;


/**
 * Implementation of {@link SQLInput} supporting a single read that retrieves the
 * attribute from the given column of a {@link PgCallableStatement}.
 */
// TODO: This class might be completely unnecessary since we're able to delegate to the underlying PgResultSet
class PgCallableStatementSQLInput extends PgSQLInput {

  private final PgCallableStatement cstmt;

  private final int parameterIndex;

  private final Map<String, Class<?>> typemap;

  PgCallableStatementSQLInput(PgCallableStatement result, int parameterIndex, Map<String, Class<?>> typemap) {
    this.cstmt = result;
    this.parameterIndex = parameterIndex;
    this.typemap = typemap;
  }

  @Override
  public String readString() throws SQLException {
    markRead();
    return cstmt.getString(parameterIndex);
  }

  @Override
  public boolean readBoolean() throws SQLException {
    markRead();
    return cstmt.getBoolean(parameterIndex);
  }

  @Override
  public byte readByte() throws SQLException {
    markRead();
    return cstmt.getByte(parameterIndex);
  }

  @Override
  public short readShort() throws SQLException {
    markRead();
    return cstmt.getShort(parameterIndex);
  }

  @Override
  public int readInt() throws SQLException {
    markRead();
    return cstmt.getInt(parameterIndex);
  }

  @Override
  public long readLong() throws SQLException {
    markRead();
    return cstmt.getLong(parameterIndex);
  }

  @Override
  public float readFloat() throws SQLException {
    markRead();
    return cstmt.getFloat(parameterIndex);
  }

  @Override
  public double readDouble() throws SQLException {
    markRead();
    return cstmt.getDouble(parameterIndex);
  }

  @Override
  public BigDecimal readBigDecimal() throws SQLException {
    markRead();
    return cstmt.getBigDecimal(parameterIndex);
  }

  @Override
  public byte[] readBytes() throws SQLException {
    markRead();
    return cstmt.getBytes(parameterIndex);
  }

  @Override
  public Date readDate() throws SQLException {
    markRead();
    return cstmt.getDate(parameterIndex);
  }

  @Override
  public Time readTime() throws SQLException {
    markRead();
    return cstmt.getTime(parameterIndex);
  }

  @Override
  public Timestamp readTimestamp() throws SQLException {
    markRead();
    return cstmt.getTimestamp(parameterIndex);
  }

  @Override
  public Reader readCharacterStream() throws SQLException {
    markRead();
    return cstmt.getCharacterStream(parameterIndex);
  }

  /**
   * {@inheritDoc}
   * <p>
   * {@link CallableStatement} does not define any form of {@code getAsciiStream()},
   * which is rather inconsistent with the rest of the JDBC API.  We are performing
   * a conversion from {@link CallableStatement#getString(int)} here based on the
   * implementation of {@link PgResultSet#getAsciiStream(int)}.
   * </p>
   * <p>
   * Should {@link CallableStatement} ever get the {@code getAsciiStream()} method
   * in a future version of JDBC, calling that method would be preferred.
   * </p>
   */
  @Override
  public InputStream readAsciiStream() throws SQLException {
    markRead();
    return cstmt.getAsciiStream(parameterIndex);
  }

  /**
   * {@inheritDoc}
   * <p>
   * {@link CallableStatement} does not define any form of {@code getBinaryStream()},
   * which is rather inconsistent with the rest of the JDBC API.  We are performing
   * a conversion from {@link CallableStatement#getBytes(int)} here based on the
   * implementation of {@link PgResultSet#getBinaryStream(int)}.
   * </p>
   * <p>
   * Should {@link CallableStatement} ever get the {@code getBinaryStream()} method
   * in a future version of JDBC, calling that method would be preferred.
   * </p>
   */
  @Override
  public InputStream readBinaryStream() throws SQLException {
    markRead();
    return cstmt.getBinaryStream(parameterIndex);
  }

  @Override
  public Object readObject() throws SQLException {
    markRead();
    // Avoid unbounded recursion
    throw new PSQLException(GT.tr(
        "To avoid stack overflow, SQLInput.readObject() does not support getting objects."),
        PSQLState.NOT_IMPLEMENTED);
    //return cstmt.getObject(parameterIndex, typemap);
  }

  @Override
  public Ref readRef() throws SQLException {
    markRead();
    return cstmt.getRef(parameterIndex);
  }

  @Override
  public Blob readBlob() throws SQLException {
    markRead();
    return cstmt.getBlob(parameterIndex);
  }

  @Override
  public Clob readClob() throws SQLException {
    markRead();
    return cstmt.getClob(parameterIndex);
  }

  @Override
  public Array readArray() throws SQLException {
    markRead();
    return cstmt.getArray(parameterIndex);
  }

  /**
   * {@inheritDoc}
   * <p>
   * {@link PgResultSet#wasNull()} returns {@code false} when no field has been accessed.  This seems a bit lenient.
   * Here we are throwing an exception if no read yet performed.
   * </p>
   */
  @Override
  public boolean wasNull() throws SQLException {
    if (!getReadDone()) {
      throw new PSQLException(GT.tr(
          "SQLInput.wasNull() called before any read."),
          PSQLState.NO_DATA);
    }
    return cstmt.wasNull();
  }

  @Override
  public URL readURL() throws SQLException {
    markRead();
    return cstmt.getURL(parameterIndex);
  }

  @Override
  public NClob readNClob() throws SQLException {
    markRead();
    return cstmt.getNClob(parameterIndex);
  }

  @Override
  public String readNString() throws SQLException {
    markRead();
    return cstmt.getNString(parameterIndex);
  }

  @Override
  public SQLXML readSQLXML() throws SQLException {
    markRead();
    return cstmt.getSQLXML(parameterIndex);
  }

  @Override
  public RowId readRowId() throws SQLException {
    markRead();
    return cstmt.getRowId(parameterIndex);
  }

  /**
   * {@inheritDoc}
   * <p>
   * This avoids the ambiguity of types, and thus is not necessarily subject to the
   * unbounded recursion inherent in the {@link #readObject()} implementation.
   * </p>
   */
  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  //#endif
  public <T> T readObject(Class<T> type) throws SQLException {
    markRead();
    return cstmt.getObjectImpl(parameterIndex, type, typemap);
  }
}
