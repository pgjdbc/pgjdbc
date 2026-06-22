/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
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
 * Codec for PostgreSQL float8 (DOUBLE PRECISION) type.
 */
public final class Float8Codec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final Float8Codec INSTANCE = new Float8Codec();

  // Constants for overflow checking (from PgResultSet)
  private static final double LONG_MAX_DOUBLE = Long.MAX_VALUE;
  private static final double LONG_MIN_DOUBLE = Long.MIN_VALUE;

  private Float8Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "float8";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is digits/sign/dot/e or NaN/Infinity — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Double.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return Float8ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsDouble(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    if (length != 8) {
      throw new PSQLException(
          GT.tr("Invalid float8 binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.float8(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    double v = toDouble(value);
    byte[] result = new byte[8];
    ByteConverter.float8(result, 0, v);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsDouble(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return String.valueOf(toDouble(value));
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 8) {
      throw new PSQLException(
          GT.tr("Invalid float8 binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.float8(data, 0);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Double.parseDouble(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to double: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public float decodeAsFloat(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return (float) decodeAsDouble(data, type, ctx);
  }

  @Override
  public float decodeAsFloat(String data, PgType type, CodecContext ctx) throws SQLException {
    return (float) decodeAsDouble(data, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    if (value < LONG_MIN_DOUBLE || value > LONG_MAX_DOUBLE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for long", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (long) value;
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    if (value < LONG_MIN_DOUBLE || value > LONG_MAX_DOUBLE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for long", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (long) value;
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw new PSQLException(
          GT.tr("Cannot convert {0} to BigDecimal", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return BigDecimal.valueOf(value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    if (targetClass == Double.class || targetClass == Object.class) {
      return (T) Double.valueOf(value);
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf((float) value);
    }
    if (targetClass == Integer.class) {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for int", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Integer.valueOf((int) value);
    }
    if (targetClass == Long.class) {
      if (value < LONG_MIN_DOUBLE || value > LONG_MAX_DOUBLE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for long", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Long.valueOf((long) value);
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
    if (targetClass == BigDecimal.class) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        throw new PSQLException(
            GT.tr("Cannot convert {0} to BigDecimal", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) BigDecimal.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw Codec.cannotDecode("float8", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    byte[] bytes = new byte[8];
    ByteConverter.float8(bytes, 0, value);
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  static double toDouble(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof String) {
      try {
        return Double.parseDouble(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to double: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1.0 : 0.0;
    }
    throw Codec.cannotEncode(value, "float8");
  }
}
