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
import org.postgresql.util.NumberParser;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL int4 (INTEGER) type.
 */
public final class Int4Codec implements BinaryCodec, TextCodec {

  public static final Int4Codec INSTANCE = new Int4Codec();

  private Int4Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "int4";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Integer.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    int v = toInt(value);
    byte[] result = new byte[4];
    ByteConverter.int4(result, 0, v);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return String.valueOf(toInt(value));
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 4) {
      throw new PSQLException(
          GT.tr("Invalid int4 binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.int4(data, 0);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Integer.parseInt(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to int: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public int decodeTextBytesAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    // Fast path for ASCII-encoded integers
    if (ctx.getEncoding().hasAsciiNumbers()) {
      try {
        return (int) NumberParser.getFastLong(data, Integer.MIN_VALUE, Integer.MAX_VALUE);
      } catch (NumberFormatException ignored) {
        // Fall through to string parsing
      }
    }
    return decodeAsInt(new String(data, java.nio.charset.StandardCharsets.UTF_8), type, ctx);
  }

  @Override
  public long decodeTextBytesAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeTextBytesAsInt(data, type, ctx);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsInt(data, type, ctx));
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    int value = decodeAsInt(data, type, ctx);
    if (targetClass == Integer.class || targetClass == Object.class) {
      return (T) Integer.valueOf(value);
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(value);
    }
    if (targetClass == Short.class) {
      if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for short", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Short.valueOf((short) value);
    }
    if (targetClass == Byte.class) {
      if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for byte", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Byte.valueOf((byte) value);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(value);
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf(value);
    }
    if (targetClass == BigDecimal.class) {
      return (T) BigDecimal.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw new PSQLException(
        GT.tr("Cannot convert int4 to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    int value = decodeAsInt(data, type, ctx);
    // Delegate to binary version since we have the int value
    byte[] bytes = new byte[4];
    ByteConverter.int4(bytes, 0, value);
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  private int toInt(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to int: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1 : 0;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to int", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
