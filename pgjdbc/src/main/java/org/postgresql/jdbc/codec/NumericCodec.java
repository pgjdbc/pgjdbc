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
 * Codec for PostgreSQL numeric (DECIMAL) type.
 */
public final class NumericCodec implements BinaryCodec, TextCodec {

  public static final NumericCodec INSTANCE = new NumericCodec();

  // Constants for overflow checking
  private static final double LONG_MAX_DOUBLE = Long.MAX_VALUE;
  private static final double LONG_MIN_DOUBLE = Long.MIN_VALUE;

  private NumericCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "numeric";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return BigDecimal.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsBigDecimal(data, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    BigDecimal bd = toBigDecimal(value);
    return ByteConverter.numeric(bd);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsBigDecimal(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    BigDecimal bd = toBigDecimal(value);
    return bd.toPlainString();
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Number result = ByteConverter.numeric(data);
    if (result instanceof BigDecimal) {
      return (BigDecimal) result;
    }
    // Special values (NaN, +Inf, -Inf) are returned as Double
    if (result instanceof Double) {
      double d = result.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        throw new PSQLException(
            GT.tr("Cannot convert {0} to BigDecimal", result),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
    }
    // Fallback - shouldn't happen
    return BigDecimal.valueOf(result.doubleValue());
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, PgType type, CodecContext ctx) throws SQLException {
    String trimmed = data.trim();
    if ("NaN".equalsIgnoreCase(trimmed)) {
      throw new PSQLException(
          GT.tr("Cannot convert NaN to BigDecimal"),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    if ("Infinity".equalsIgnoreCase(trimmed) || "+Infinity".equalsIgnoreCase(trimmed)
        || "-Infinity".equalsIgnoreCase(trimmed)) {
      throw new PSQLException(
          GT.tr("Cannot convert {0} to BigDecimal", trimmed),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    try {
      return new BigDecimal(trimmed);
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to numeric: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Number result = ByteConverter.numeric(data);
    return result.doubleValue();
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    String trimmed = data.trim();
    if ("NaN".equalsIgnoreCase(trimmed)) {
      return Double.NaN;
    }
    if ("Infinity".equalsIgnoreCase(trimmed) || "+Infinity".equalsIgnoreCase(trimmed)) {
      return Double.POSITIVE_INFINITY;
    }
    if ("-Infinity".equalsIgnoreCase(trimmed)) {
      return Double.NEGATIVE_INFINITY;
    }
    try {
      return Double.parseDouble(trimmed);
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
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    if (bd == null) {
      return 0;
    }
    double d = bd.doubleValue();
    if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", bd),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return bd.intValue();
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    if (bd == null) {
      return 0;
    }
    double d = bd.doubleValue();
    if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", bd),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return bd.intValue();
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    if (bd == null) {
      return 0;
    }
    double d = bd.doubleValue();
    if (d < LONG_MIN_DOUBLE || d > LONG_MAX_DOUBLE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for long", bd),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return bd.longValue();
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    if (bd == null) {
      return 0;
    }
    double d = bd.doubleValue();
    if (d < LONG_MIN_DOUBLE || d > LONG_MAX_DOUBLE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for long", bd),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return bd.longValue();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == BigDecimal.class || targetClass == Object.class) {
      return (T) decodeAsBigDecimal(data, type, ctx);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(decodeAsDouble(data, type, ctx));
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf(decodeAsFloat(data, type, ctx));
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(decodeAsLong(data, type, ctx));
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf(decodeAsInt(data, type, ctx));
    }
    if (targetClass == Short.class) {
      BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
      if (bd == null) {
        return null;
      }
      double d = bd.doubleValue();
      if (d < Short.MIN_VALUE || d > Short.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for short", bd),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Short.valueOf(bd.shortValue());
    }
    if (targetClass == Byte.class) {
      BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
      if (bd == null) {
        return null;
      }
      double d = bd.doubleValue();
      if (d < Byte.MIN_VALUE || d > Byte.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for byte", bd),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Byte.valueOf(bd.byteValue());
    }
    if (targetClass == String.class) {
      BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
      return bd == null ? null : (T) bd.toPlainString();
    }
    if (targetClass == Boolean.class) {
      BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
      return bd == null ? null : (T) Boolean.valueOf(bd.compareTo(BigDecimal.ZERO) != 0);
    }
    throw new PSQLException(
        GT.tr("Cannot convert numeric to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    // Convert to binary and delegate
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    if (bd == null) {
      return null;
    }
    byte[] bytes = ByteConverter.numeric(bd);
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  private BigDecimal toBigDecimal(Object value) throws SQLException {
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    if (value instanceof Number) {
      if (value instanceof Long || value instanceof Integer
          || value instanceof Short || value instanceof Byte) {
        return BigDecimal.valueOf(((Number) value).longValue());
      }
      return BigDecimal.valueOf(((Number) value).doubleValue());
    }
    if (value instanceof String) {
      try {
        return new BigDecimal(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to numeric: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to numeric", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
