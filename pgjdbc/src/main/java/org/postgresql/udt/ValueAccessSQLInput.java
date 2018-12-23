/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.udt;

import org.postgresql.jdbc.PgResultSet;
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


/**
 * Implementation of {@link SQLInput} supporting a single read that retrieves the
 * attribute from a {@link ValueAccess}.
 */
public class ValueAccessSQLInput extends SingleAttributeSQLInput {

  private final ValueAccess access;
  private final UdtMap udtMap;

  /**
   * Do not call this directly, but instead use {@link ValueAccess#getSQLInput(org.postgresql.udt.UdtMap)},
   * as the implementation of {@link SingleAttributeSQLInput} to used depends
   * on the {@link ValueAccess}.
   *
   * @param access the {@link ValueAccess} to use as a source of attributes
   * @param udtMap the current user-defined data type mapping
   */
  public ValueAccessSQLInput(ValueAccess access, UdtMap udtMap) {
    this.access = access;
    this.udtMap = udtMap;
  }

  @Override
  public String readString() throws SQLException {
    markRead();
    return access.getString();
  }

  @Override
  public boolean readBoolean() throws SQLException {
    markRead();
    return access.getBoolean();
  }

  @Override
  public byte readByte() throws SQLException {
    markRead();
    return access.getByte();
  }

  @Override
  public short readShort() throws SQLException {
    markRead();
    return access.getShort();
  }

  @Override
  public int readInt() throws SQLException {
    markRead();
    return access.getInt();
  }

  @Override
  public long readLong() throws SQLException {
    markRead();
    return access.getLong();
  }

  @Override
  public float readFloat() throws SQLException {
    markRead();
    return access.getFloat();
  }

  @Override
  public double readDouble() throws SQLException {
    markRead();
    return access.getDouble();
  }

  @Override
  public BigDecimal readBigDecimal() throws SQLException {
    markRead();
    return access.getBigDecimal();
  }

  @Override
  public byte[] readBytes() throws SQLException {
    markRead();
    return access.getBytes();
  }

  @Override
  public Date readDate() throws SQLException {
    markRead();
    return access.getDate();
  }

  @Override
  public Time readTime() throws SQLException {
    markRead();
    return access.getTime();
  }

  @Override
  public Timestamp readTimestamp() throws SQLException {
    markRead();
    return access.getTimestamp();
  }

  @Override
  public Reader readCharacterStream() throws SQLException {
    markRead();
    return access.getCharacterStream();
  }

  @Override
  public InputStream readAsciiStream() throws SQLException {
    markRead();
    return access.getAsciiStream();
  }

  @Override
  public InputStream readBinaryStream() throws SQLException {
    markRead();
    return access.getBinaryStream();
  }

  @Override
  public Object readObject() throws SQLException {
    markRead();
    // TODO: What to do with udtMap?
    return access.getObject();
  }

  @Override
  public Ref readRef() throws SQLException {
    markRead();
    return access.getRef();
  }

  @Override
  public Blob readBlob() throws SQLException {
    markRead();
    return access.getBlob();
  }

  @Override
  public Clob readClob() throws SQLException {
    markRead();
    return access.getClob();
  }

  @Override
  public Array readArray() throws SQLException {
    markRead();
    return access.getArray();
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
    return access.wasNull();
  }

  @Override
  public URL readURL() throws SQLException {
    markRead();
    return access.getURL();
  }

  @Override
  public NClob readNClob() throws SQLException {
    markRead();
    return access.getNClob();
  }

  @Override
  public String readNString() throws SQLException {
    markRead();
    return access.getNString();
  }

  @Override
  public SQLXML readSQLXML() throws SQLException {
    markRead();
    return access.getSQLXML();
  }

  @Override
  public RowId readRowId() throws SQLException {
    markRead();
    return access.getRowId();
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.2"
  @Override
  //#endif
  public <T> T readObject(Class<T> type) throws SQLException {
    markRead();
    // TODO: What to do with udtMap?
    return access.getObject(type);
  }
}
