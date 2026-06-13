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
 * Codec for PostgreSQL int8 (BIGINT) type.
 */
public final class Int8Codec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final Int8Codec INSTANCE = new Int8Codec();

  private Int8Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "int8";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Long.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return Int8ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    if (length != 8) {
      throw new PSQLException(
          GT.tr("Invalid int8 binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.int8(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    long v = toLong(value);
    byte[] result = new byte[8];
    ByteConverter.int8(result, 0, v);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    try {
      return NumberParser.getFastLong(data, offset, length, Long.MIN_VALUE, Long.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // Anything the fast path rejects (a leading '+', whitespace, out-of-range)
      // falls back to the String parser, which owns the error message.
      return decodeText(new String(data, offset, length), type, ctx);
    }
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return String.valueOf(toLong(value));
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 8) {
      throw new PSQLException(
          GT.tr("Invalid int8 binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.int8(data, 0);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Long.parseLong(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to long: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public long decodeTextBytesAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    // Fast path for ASCII-encoded longs
    if (ctx.getEncoding().hasAsciiNumbers()) {
      try {
        return NumberParser.getFastLong(data, Long.MIN_VALUE, Long.MAX_VALUE);
      } catch (NumberFormatException ignored) {
        // Fall through to string parsing
      }
    }
    return decodeAsLong(new String(data, java.nio.charset.StandardCharsets.UTF_8), type, ctx);
  }

  @Override
  public int decodeTextBytesAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    long value = decodeTextBytesAsLong(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
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
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for int", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Integer.valueOf((int) value);
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
      return (T) Double.valueOf((double) value);
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf((float) value);
    }
    if (targetClass == BigDecimal.class) {
      return (T) BigDecimal.valueOf(value);
    }
    if (targetClass == java.math.BigInteger.class) {
      return (T) java.math.BigInteger.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw new PSQLException(
        GT.tr("Cannot convert int8 to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    byte[] bytes = new byte[8];
    ByteConverter.int8(bytes, 0, value);
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  static long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to long: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1L : 0L;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to int8", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
