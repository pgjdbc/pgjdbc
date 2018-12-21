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
 * attribute from the given column of a {@link PgResultSet}.
 */
class PgResultSetSQLInput extends PgSQLInput {

  private final PgResultSet result;

  private final int columnIndex;

  private final Map<String, Class<?>> typemap;

  PgResultSetSQLInput(PgResultSet result, int columnIndex, Map<String, Class<?>> typemap) {
    this.result = result;
    this.columnIndex = columnIndex;
    this.typemap = typemap;
  }

  @Override
  public String readString() throws SQLException {
    markRead();
    return result.getString(columnIndex);
  }

  @Override
  public boolean readBoolean() throws SQLException {
    markRead();
    return result.getBoolean(columnIndex);
  }

  @Override
  public byte readByte() throws SQLException {
    markRead();
    return result.getByte(columnIndex);
  }

  @Override
  public short readShort() throws SQLException {
    markRead();
    return result.getShort(columnIndex);
  }

  @Override
  public int readInt() throws SQLException {
    markRead();
    return result.getInt(columnIndex);
  }

  @Override
  public long readLong() throws SQLException {
    markRead();
    return result.getLong(columnIndex);
  }

  @Override
  public float readFloat() throws SQLException {
    markRead();
    return result.getFloat(columnIndex);
  }

  @Override
  public double readDouble() throws SQLException {
    markRead();
    return result.getDouble(columnIndex);
  }

  @Override
  public BigDecimal readBigDecimal() throws SQLException {
    markRead();
    return result.getBigDecimal(columnIndex);
  }

  @Override
  public byte[] readBytes() throws SQLException {
    markRead();
    return result.getBytes(columnIndex);
  }

  @Override
  public Date readDate() throws SQLException {
    markRead();
    return result.getDate(columnIndex);
  }

  @Override
  public Time readTime() throws SQLException {
    markRead();
    return result.getTime(columnIndex);
  }

  @Override
  public Timestamp readTimestamp() throws SQLException {
    markRead();
    return result.getTimestamp(columnIndex);
  }

  @Override
  public Reader readCharacterStream() throws SQLException {
    markRead();
    return result.getCharacterStream(columnIndex);
  }

  @Override
  public InputStream readAsciiStream() throws SQLException {
    markRead();
    return result.getAsciiStream(columnIndex);
  }

  @Override
  public InputStream readBinaryStream() throws SQLException {
    markRead();
    return result.getBinaryStream(columnIndex);
  }

  @Override
  public Object readObject() throws SQLException {
    markRead();
    // Avoid unbounded recursion
    throw new PSQLException(GT.tr(
        "To avoid stack overflow, SQLInput.readObject() does not support getting objects."),
        PSQLState.NOT_IMPLEMENTED);
    //return result.getObject(columnIndex, typemap);
  }

  @Override
  public Ref readRef() throws SQLException {
    markRead();
    return result.getRef(columnIndex);
  }

  @Override
  public Blob readBlob() throws SQLException {
    markRead();
    return result.getBlob(columnIndex);
  }

  @Override
  public Clob readClob() throws SQLException {
    markRead();
    return result.getClob(columnIndex);
  }

  @Override
  public Array readArray() throws SQLException {
    markRead();
    return result.getArray(columnIndex);
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
    return result.wasNull();
  }

  @Override
  public URL readURL() throws SQLException {
    markRead();
    return result.getURL(columnIndex);
  }

  @Override
  public NClob readNClob() throws SQLException {
    markRead();
    return result.getNClob(columnIndex);
  }

  @Override
  public String readNString() throws SQLException {
    markRead();
    return result.getNString(columnIndex);
  }

  @Override
  public SQLXML readSQLXML() throws SQLException {
    markRead();
    return result.getSQLXML(columnIndex);
  }

  @Override
  public RowId readRowId() throws SQLException {
    markRead();
    return result.getRowId(columnIndex);
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
    return result.getObjectImpl(columnIndex, type, typemap);
  }
}
