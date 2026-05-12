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
 * Codec for PostgreSQL jsonb type.
 *
 * <p>Returns String for getObject(). No JSON library integration is provided;
 * applications should parse the JSON string themselves.</p>
 *
 * <p>Binary format includes a version byte prefix (currently always 1).</p>
 */
public final class JsonbCodec implements BinaryCodec, TextCodec {

  public static final JsonbCodec INSTANCE = new JsonbCodec();

  /** JSONB binary format version (currently 1) */
  private static final byte JSONB_VERSION = 1;

  private JsonbCodec() {
  }

  @Override
  public String getTypeName() {
    return "jsonb";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return String.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    // Skip version byte
    if (data.length < 1) {
      return "";
    }
    return new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    String str = value.toString();
    byte[] jsonBytes = str.getBytes(StandardCharsets.UTF_8);
    // Add version byte prefix
    byte[] result = new byte[jsonBytes.length + 1];
    result[0] = JSONB_VERSION;
    System.arraycopy(jsonBytes, 0, result, 1, jsonBytes.length);
    return result;
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
  public String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    // Skip version byte
    if (data.length < 1) {
      return "";
    }
    return new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
  }

  @Override
  public String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert jsonb to int"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert jsonb to int"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert jsonb to long"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert jsonb to long"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert jsonb to double"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert jsonb to double"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    String value = decodeAsString(data, type, ctx);
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) value;
    }
    throw new PSQLException(
        GT.tr("Cannot convert jsonb to {0}", targetClass.getName()),
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
        GT.tr("Cannot convert jsonb to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
