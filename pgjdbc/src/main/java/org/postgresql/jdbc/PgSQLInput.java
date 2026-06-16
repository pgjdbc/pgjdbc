/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.nio.charset.StandardCharsets.US_ASCII;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
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
import java.util.List;

/**
 * Base class for SQLInput implementations.
 * Uses generic BufferType to avoid code duplication between binary and text formats.
 *
 * @param <BufferType> the type of buffer for attribute values (byte[] for binary, String for text)
 */
@SuppressWarnings({"override.return", "override.param"})
public abstract class PgSQLInput<BufferType> implements SQLInput {

  protected final @Nullable BufferType[] attributeValues;
  protected final PgType compositeType;
  protected final CodecContext ctx;
  protected final List<PgField> fields;
  protected int fieldIndex = 0;
  protected boolean lastWasNull = false;

  /**
   * Creates a new PgSQLInput.
   *
   * @param attributeValues the parsed attribute values (null for SQL NULL)
   * @param type the composite type
   * @param ctx the codec context
   */
  protected PgSQLInput(@Nullable BufferType[] attributeValues, PgType type, CodecContext ctx)
      throws SQLException {
    this.attributeValues = attributeValues;
    this.compositeType = type;
    this.ctx = ctx;
    List<PgField> typeFields = type.getFields();
    // Fields are loaded lazily for composite types: fall back to the type info
    // cache when the PgType instance hasn't materialized them yet.
    this.fields = typeFields != null ? typeFields : ctx.getTypeInfo().getFields(type.getOid());
  }

  @Override
  public boolean wasNull() throws SQLException {
    return lastWasNull;
  }

  /**
   * Gets the current field and advances the index.
   *
   * @return the current field
   * @throws SQLException if no more fields
   */
  protected PgField nextField() throws SQLException {
    if (fieldIndex >= fields.size()) {
      throw new PSQLException(
          GT.tr("Attempt to read past end of composite type fields"),
          PSQLState.DATA_ERROR);
    }
    return fields.get(fieldIndex);
  }

  /**
   * Gets the current attribute value and advances the index.
   *
   * @return the current attribute value, or null for SQL NULL
   */
  protected @Nullable BufferType nextValue() {
    if (fieldIndex >= attributeValues.length) {
      lastWasNull = true;
      fieldIndex++;
      return null;
    }
    BufferType val = attributeValues[fieldIndex++];
    lastWasNull = (val == null);
    return val;
  }

  /**
   * Gets the PgType for a field.
   */
  protected PgType getFieldType(PgField field) throws SQLException {
    return ctx.getTypeInfo().getPgTypeByOid(field.getTypeOid());
  }

  // Abstract decode methods to be implemented by subclasses.
  // Note: data is guaranteed non-null by the caller (readXxx methods check for null first).
  protected abstract int decodeInt(BufferType data, PgType fieldType) throws SQLException;

  protected abstract long decodeLong(BufferType data, PgType fieldType) throws SQLException;

  protected abstract double decodeDouble(BufferType data, PgType fieldType) throws SQLException;

  protected abstract float decodeFloat(BufferType data, PgType fieldType) throws SQLException;

  protected abstract boolean decodeBoolean(BufferType data, PgType fieldType) throws SQLException;

  protected abstract @Nullable String decodeString(BufferType data, PgType fieldType) throws SQLException;

  protected abstract @Nullable BigDecimal decodeBigDecimal(BufferType data, PgType fieldType)
      throws SQLException;

  protected abstract byte @Nullable [] decodeBytes(BufferType data, PgType fieldType) throws SQLException;

  protected abstract @Nullable Date decodeDate(BufferType data, PgType fieldType) throws SQLException;

  protected abstract @Nullable Time decodeTime(BufferType data, PgType fieldType) throws SQLException;

  protected abstract @Nullable Timestamp decodeTimestamp(BufferType data, PgType fieldType)
      throws SQLException;

  protected abstract @Nullable Object decodeObject(BufferType data, PgType fieldType) throws SQLException;

  protected abstract Array decodeArray(BufferType data, PgType fieldType) throws SQLException;

  protected abstract <T> @Nullable T decodeObjectAs(BufferType data, PgType fieldType, Class<T> type)
      throws SQLException;

  // SQLInput implementation methods

  @Override
  public @Nullable String readString() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeString(data, getFieldType(field));
  }

  @Override
  public boolean readBoolean() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return false;
    }
    return decodeBoolean(data, getFieldType(field));
  }

  @Override
  public byte readByte() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return 0;
    }
    return (byte) decodeInt(data, getFieldType(field));
  }

  @Override
  public short readShort() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return 0;
    }
    return (short) decodeInt(data, getFieldType(field));
  }

  @Override
  public int readInt() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return 0;
    }
    return decodeInt(data, getFieldType(field));
  }

  @Override
  public long readLong() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return 0;
    }
    return decodeLong(data, getFieldType(field));
  }

  @Override
  public float readFloat() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return 0;
    }
    return decodeFloat(data, getFieldType(field));
  }

  @Override
  public double readDouble() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return 0;
    }
    return decodeDouble(data, getFieldType(field));
  }

  @Override
  public @Nullable BigDecimal readBigDecimal() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeBigDecimal(data, getFieldType(field));
  }

  @Override
  public byte @Nullable [] readBytes() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeBytes(data, getFieldType(field));
  }

  @Override
  public @Nullable Date readDate() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeDate(data, getFieldType(field));
  }

  @Override
  public @Nullable Time readTime() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeTime(data, getFieldType(field));
  }

  @Override
  public @Nullable Timestamp readTimestamp() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeTimestamp(data, getFieldType(field));
  }

  @Override
  public @Nullable Reader readCharacterStream() throws SQLException {
    String s = readString();
    return s == null ? null : new StringReader(s);
  }

  @Override
  public @Nullable InputStream readAsciiStream() throws SQLException {
    String s = readString();
    return s == null ? null : new ByteArrayInputStream(s.getBytes(US_ASCII));
  }

  @Override
  public @Nullable InputStream readBinaryStream() throws SQLException {
    byte[] bytes = readBytes();
    return bytes == null ? null : new ByteArrayInputStream(bytes);
  }

  @Override
  public @Nullable Object readObject() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeObject(data, getFieldType(field));
  }

  @Override
  public Ref readRef() throws SQLException {
    throw new PSQLException(GT.tr("readRef() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public Blob readBlob() throws SQLException {
    throw new PSQLException(GT.tr("readBlob() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public Clob readClob() throws SQLException {
    throw new PSQLException(GT.tr("readClob() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public @Nullable Array readArray() throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeArray(data, getFieldType(field));
  }

  @Override
  public @Nullable URL readURL() throws SQLException {
    String s = readString();
    if (s == null) {
      return null;
    }
    try {
      return new URL(s);
    } catch (MalformedURLException e) {
      throw new PSQLException(GT.tr("Invalid URL: {0}", s), PSQLState.DATA_ERROR, e);
    }
  }

  @Override
  public NClob readNClob() throws SQLException {
    throw new PSQLException(GT.tr("readNClob() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public @Nullable String readNString() throws SQLException {
    return readString();
  }

  @Override
  public @Nullable SQLXML readSQLXML() throws SQLException {
    String s = readString();
    return s == null ? null : new PgSQLXML(ctx.getConnection(), s);
  }

  @Override
  public RowId readRowId() throws SQLException {
    throw new PSQLException(GT.tr("readRowId() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public <T> @Nullable T readObject(Class<T> type) throws SQLException {
    PgField field = nextField();
    BufferType data = nextValue();
    if (data == null) {
      return null;
    }
    return decodeObjectAs(data, getFieldType(field), type);
  }
}
