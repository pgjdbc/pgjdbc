/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL xml type.
 *
 * <p>Returns String for getObject(). SQLXML handling is done at the ResultSet level
 * using PgSQLXML wrapper.</p>
 */
public final class XmlCodec implements BinaryCodec, TextCodec {

  public static final XmlCodec INSTANCE = new XmlCodec();

  private XmlCodec() {
  }

  @Override
  public String getTypeName() {
    return "xml";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    // SQLXML matches the legacy ResultSetMetaData contract; PgResultSet.getObject
    // produces the PgSQLXML wrapper via getSQLXML() rather than the codec's
    // decodeText (which still returns a String for codec-level callers).
    return java.sql.SQLXML.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    return new String(data, StandardCharsets.UTF_8);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    String str = value.toString();
    return str.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    return new String(data, StandardCharsets.UTF_8);
  }

  @Override
  public String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert xml to int"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert xml to int"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert xml to long"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert xml to long"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert xml to double"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert xml to double"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    String value = new String(data, StandardCharsets.UTF_8);
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) value;
    }
    throw new PSQLException(
        GT.tr("Cannot convert xml to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert xml to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
