/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL oid type.
 *
 * <p>OID is an unsigned 32-bit integer, represented as Long in Java
 * to handle the full range without overflow.</p>
 */
public final class OidCodec implements BinaryCodec, TextCodec {

  public static final OidCodec INSTANCE = new OidCodec();

  private OidCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "oid";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Long.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    long v = toLong(value);
    byte[] result = new byte[4];
    ByteConverter.int4(result, 0, (int) v);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return String.valueOf(toLong(value));
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 4) {
      throw new PSQLException(
          GT.tr("Invalid oid binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.int4(data, 0);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      long v = Long.parseLong(data.trim());
      return (int) v;
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to oid: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 4) {
      throw new PSQLException(
          GT.tr("Invalid oid binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    // Treat as unsigned 32-bit
    return ByteConverter.int4(data, 0) & 0xFFFFFFFFL;
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Long.parseLong(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to oid: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsLong(data, type, ctx));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    if (targetClass == Long.class || targetClass == Object.class) {
      return (T) Long.valueOf(value);
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf((int) value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(value);
    }
    if (targetClass == BigDecimal.class) {
      return (T) BigDecimal.valueOf(value);
    }
    throw new PSQLException(
        GT.tr("Cannot convert oid to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    byte[] bytes = new byte[4];
    ByteConverter.int4(bytes, 0, (int) value);
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  private long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to oid: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to oid", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
