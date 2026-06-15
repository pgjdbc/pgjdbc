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
import java.math.RoundingMode;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL numeric (DECIMAL) type.
 */
public final class NumericCodec implements BinaryCodec, TextCodec {

  public static final NumericCodec INSTANCE = new NumericCodec();

  // Constants for overflow checking. Comparing against double-valued bounds
  // would silently accept values like 9223372036854775808, because (double)
  // Long.MAX_VALUE rounds up to 9223372036854775808.0 (52-bit mantissa).
  // Use BigDecimal for exact comparison.
  private static final BigDecimal LONG_MAX_BD = BigDecimal.valueOf(Long.MAX_VALUE);
  private static final BigDecimal LONG_MIN_BD = BigDecimal.valueOf(Long.MIN_VALUE);

  private NumericCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "numeric";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is digits/sign/dot/e or NaN — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return BigDecimal.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeBinary(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    // PostgreSQL numeric supports NaN / ±Infinity (the latter since v14).
    // BigDecimal can't represent them, so surface those literals as Double
    // sentinels — matches the legacy driver's getObject contract. Callers
    // that need BigDecimal go through decodeAsBigDecimal which throws.
    Number result = ByteConverter.numeric(data, offset, length);
    if (result instanceof Double) {
      double d = result.doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return d;
      }
    }
    if (result instanceof BigDecimal) {
      return result;
    }
    return BigDecimal.valueOf(result.doubleValue());
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    BigDecimal bd = toBigDecimal(value);
    return ByteConverter.numeric(bd);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null) {
      return null;
    }
    // PostgreSQL numeric supports NaN / ±Infinity (the latter since v14).
    // BigDecimal can't represent them, so surface those literals as Double
    // sentinels — matches the legacy driver's getObject contract.
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
        // Pass a literal token rather than the Double itself so MessageFormat
        // does not apply locale-aware number formatting (e.g. "не число").
        String token;
        if (Double.isNaN(d)) {
          token = "NaN";
        } else if (d == Double.POSITIVE_INFINITY) {
          token = "Infinity";
        } else {
          token = "-Infinity";
        }
        throw new PSQLException(
            GT.tr("Bad value for type {0} : {1}", "BigDecimal", token),
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
          GT.tr("Bad value for type {0} : {1}", "BigDecimal", "NaN"),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    if ("Infinity".equalsIgnoreCase(trimmed) || "+Infinity".equalsIgnoreCase(trimmed)
        || "-Infinity".equalsIgnoreCase(trimmed)) {
      throw new PSQLException(
          GT.tr("Bad value for type {0} : {1}", "BigDecimal", trimmed),
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
    // Truncate the fractional part (matches the legacy getLong contract:
    // 9223372036854775807.9 → Long.MAX_VALUE). Then check integer-part bounds
    // exactly via BigDecimal compareTo.
    BigDecimal whole = bd.setScale(0, RoundingMode.DOWN);
    if (whole.compareTo(LONG_MAX_BD) > 0 || whole.compareTo(LONG_MIN_BD) < 0) {
      throw new PSQLException(
          GT.tr("Bad value for type {0} : {1}", "long", bd.toPlainString()),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return whole.longValue();
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    if (bd == null) {
      return 0;
    }
    // Truncate the fractional part (matches the legacy getLong contract:
    // 9223372036854775807.9 → Long.MAX_VALUE). Then check integer-part bounds
    // exactly via BigDecimal compareTo.
    BigDecimal whole = bd.setScale(0, RoundingMode.DOWN);
    if (whole.compareTo(LONG_MAX_BD) > 0 || whole.compareTo(LONG_MIN_BD) < 0) {
      throw new PSQLException(
          GT.tr("Bad value for type {0} : {1}", "long", bd.toPlainString()),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return whole.longValue();
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
    throw Codec.cannotDecode("numeric", targetClass.getName());
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
    throw Codec.cannotEncode(value, "numeric");
  }
}
