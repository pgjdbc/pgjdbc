/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.nio.charset.StandardCharsets.US_ASCII;

import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLOutput;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * Base class for SQLOutput implementations.
 * Uses generic BufferType to avoid code duplication between binary and text formats.
 *
 * @param <BufferType> the type of buffer for attribute values (byte[] for binary, String for text)
 */
public abstract class PgSQLOutput<BufferType> implements SQLOutput {

  protected final List<@Nullable BufferType> attributeValues = new ArrayList<>();
  protected final PgType compositeType;
  protected final PgCodecContext ctx;
  protected final List<PgField> fields;
  protected int fieldIndex = 0;

  /**
   * Creates a new PgSQLOutput.
   *
   * @param type the composite type
   * @param ctx the codec context
   */
  protected PgSQLOutput(PgType type, PgCodecContext ctx) throws SQLException {
    this.compositeType = type;
    this.ctx = ctx;
    List<PgField> typeFields = type.getFields();
    if (typeFields != null) {
      this.fields = typeFields;
    } else if (ctx.isConnectionBound()) {
      // Fields are loaded lazily for composite types: fall back to the type info cache.
      this.fields = ctx.getTypeInfo().getFields(type.getOid());
    } else {
      // Offline contexts have no type cache to load attributes from, so the caller must register
      // the composite type with its fields.
      throw new PSQLException(
          GT.tr("Offline composite access for {0} needs its attributes; register the type with its "
              + "fields in the offline codec context.", type.getFullName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
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
          GT.tr("Attempt to write past end of composite type fields"),
          PSQLState.DATA_ERROR);
    }
    return fields.get(fieldIndex++);
  }

  /**
   * Gets the PgType for a field.
   */
  protected PgType getFieldType(PgField field) throws SQLException {
    if (ctx.isConnectionBound()) {
      return ctx.getTypeInfo().getPgTypeByOid(field.getTypeOid());
    }
    // Offline: the result is discarded by every concrete encoder (they read the cached per-field
    // type), so resolve through the context without a type cache. Offline descriptors are PgType.
    TypeDescriptor resolved = ctx.resolveType(field.getTypeOid());
    return resolved instanceof PgType ? (PgType) resolved : compositeType;
  }

  /**
   * Adds a null value to the output.
   */
  protected void writeNull() {
    attributeValues.add(null);
  }

  /**
   * Gets the collected attribute values.
   *
   * @return the attribute values (null elements for SQL NULL)
   */
  public List<@Nullable BufferType> getAttributeValues() {
    return attributeValues;
  }

  // Abstract encode methods to be implemented by subclasses
  protected abstract BufferType encodeInt(int value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeLong(long value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeDouble(double value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeFloat(float value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeBoolean(boolean value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeString(String value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeBigDecimal(BigDecimal value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeBytes(byte[] value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeDate(Date value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeTime(Time value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeTimestamp(Timestamp value, PgType fieldType) throws SQLException;

  protected abstract BufferType encodeObject(Object value, PgType fieldType) throws SQLException;

  // SQLOutput implementation methods

  @Override
  public void writeString(@Nullable String x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeString(x, getFieldType(field)));
  }

  @Override
  public void writeBoolean(boolean x) throws SQLException {
    PgField field = nextField();
    attributeValues.add(encodeBoolean(x, getFieldType(field)));
  }

  @Override
  public void writeByte(byte x) throws SQLException {
    PgField field = nextField();
    attributeValues.add(encodeInt(x, getFieldType(field)));
  }

  @Override
  public void writeShort(short x) throws SQLException {
    PgField field = nextField();
    attributeValues.add(encodeInt(x, getFieldType(field)));
  }

  @Override
  public void writeInt(int x) throws SQLException {
    PgField field = nextField();
    attributeValues.add(encodeInt(x, getFieldType(field)));
  }

  @Override
  public void writeLong(long x) throws SQLException {
    PgField field = nextField();
    attributeValues.add(encodeLong(x, getFieldType(field)));
  }

  @Override
  public void writeFloat(float x) throws SQLException {
    PgField field = nextField();
    attributeValues.add(encodeFloat(x, getFieldType(field)));
  }

  @Override
  public void writeDouble(double x) throws SQLException {
    PgField field = nextField();
    attributeValues.add(encodeDouble(x, getFieldType(field)));
  }

  @Override
  public void writeBigDecimal(@Nullable BigDecimal x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeBigDecimal(x, getFieldType(field)));
  }

  @Override
  public void writeBytes(byte @Nullable [] x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeBytes(x, getFieldType(field)));
  }

  @Override
  public void writeDate(@Nullable Date x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeDate(x, getFieldType(field)));
  }

  @Override
  public void writeTime(@Nullable Time x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeTime(x, getFieldType(field)));
  }

  @Override
  public void writeTimestamp(@Nullable Timestamp x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeTimestamp(x, getFieldType(field)));
  }

  @Override
  public void writeCharacterStream(@Nullable Reader x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeString(readAll(x), getFieldType(field)));
  }

  @Override
  public void writeAsciiStream(@Nullable InputStream x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeString(new String(readAll(x), US_ASCII), getFieldType(field)));
  }

  @Override
  public void writeBinaryStream(@Nullable InputStream x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeBytes(readAll(x), getFieldType(field)));
  }

  /**
   * Reads a character stream to its end.
   */
  private static String readAll(Reader reader) throws SQLException {
    StringBuilder sb = new StringBuilder();
    char[] buffer = new char[8192];
    try {
      int read;
      while ((read = reader.read(buffer)) != -1) {
        sb.append(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new PSQLException(GT.tr("An I/O error occurred while reading the stream."),
          PSQLState.IO_ERROR, e);
    }
    return sb.toString();
  }

  /**
   * Reads a byte stream to its end.
   */
  private static byte[] readAll(InputStream stream) throws SQLException {
    // TODO: support ByteStreamWriter?
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    try {
      int read;
      while ((read = stream.read(buffer)) != -1) {
        baos.write(buffer, 0, read);
      }
    } catch (IOException e) {
      throw new PSQLException(GT.tr("An I/O error occurred while reading the stream."),
          PSQLState.IO_ERROR, e);
    }
    return baos.toByteArray();
  }

  @Override
  public void writeObject(@Nullable SQLData x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeObject(x, getFieldType(field)));
  }

  @Override
  public void writeRef(@Nullable Ref x) throws SQLException {
    throw new PSQLException(GT.tr("writeRef() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public void writeBlob(@Nullable Blob x) throws SQLException {
    throw new PSQLException(GT.tr("writeBlob() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public void writeClob(@Nullable Clob x) throws SQLException {
    throw new PSQLException(GT.tr("writeClob() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public void writeStruct(@Nullable Struct x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeObject(x, getFieldType(field)));
  }

  @Override
  public void writeArray(@Nullable Array x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    // Encode array using the encodeObject method which delegates to codec
    attributeValues.add(encodeObject(x, getFieldType(field)));
  }

  @Override
  public void writeURL(@Nullable URL x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeString(x.toString(), getFieldType(field)));
  }

  @Override
  public void writeNString(@Nullable String x) throws SQLException {
    writeString(x);
  }

  @Override
  public void writeNClob(@Nullable NClob x) throws SQLException {
    throw new PSQLException(GT.tr("writeNClob() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public void writeRowId(@Nullable RowId x) throws SQLException {
    throw new PSQLException(GT.tr("writeRowId() not implemented"), PSQLState.NOT_IMPLEMENTED);
  }

  @Override
  public void writeSQLXML(@Nullable SQLXML x) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    String xml = x.getString();
    if (xml == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeString(xml, getFieldType(field)));
  }

  @Override
  public void writeObject(@Nullable Object x, java.sql.SQLType targetSqlType) throws SQLException {
    PgField field = nextField();
    if (x == null) {
      writeNull();
      return;
    }
    attributeValues.add(encodeObject(x, getFieldType(field)));
  }
}
