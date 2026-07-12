/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL numeric (DECIMAL) type.
 */
public final class NumericCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder {

  public static final NumericCodec INSTANCE = new NumericCodec();

  // Constants for overflow checking. Comparing against double-valued bounds
  // would silently accept values like 9223372036854775808, because (double)
  // Long.MAX_VALUE rounds up to 9223372036854775808.0 (52-bit mantissa).
  // Use BigDecimal for exact comparison.
  private static final BigDecimal LONG_MAX_BD = BigDecimal.valueOf(Long.MAX_VALUE);
  private static final BigDecimal LONG_MIN_BD = BigDecimal.valueOf(Long.MIN_VALUE);
  private static final BigDecimal INT_MAX_BD = BigDecimal.valueOf(Integer.MAX_VALUE);
  private static final BigDecimal INT_MIN_BD = BigDecimal.valueOf(Integer.MIN_VALUE);

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
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // PostgreSQL numeric supports NaN / ±Infinity (the latter since v14).
    // BigDecimal can't represent them, so surface those literals as Double
    // sentinels — matches the legacy driver's getObject contract. Callers
    // that need BigDecimal go through decodeAsBigDecimal which throws.
    Number result = numericFromWire(data, offset, length);
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
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // numeric carries NaN / ±Infinity, but BigDecimal can't, so encode the
    // sentinel straight to the wire and skip toBigDecimal — BigDecimal.valueOf(NaN)
    // throws NumberFormatException.
    Double special = specialValue(value);
    if (special != null) {
      return ByteConverter.numericNonFinite(special);
    }
    BigDecimal bd = toBigDecimal(value);
    return ByteConverter.numeric(bd);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Mirror encodeBinary: text numeric spells the special values out as
    // literals, which decodeText reads back.
    Double special = specialValue(value);
    if (special != null) {
      double d = special;
      if (Double.isNaN(d)) {
        return "NaN";
      }
      return d > 0 ? "Infinity" : "-Infinity";
    }
    BigDecimal bd = toBigDecimal(value);
    return bd.toPlainString();
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return bigDecimalFromWire(data, offset, length);
  }

  private static @Nullable BigDecimal bigDecimalFromWire(byte[] data, int offset, int length)
      throws SQLException {
    Number result = numericFromWire(data, offset, length);
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
        throw Exceptions.badValueForType("BigDecimal", token);
      }
    }
    // Fallback - shouldn't happen
    return BigDecimal.valueOf(result.doubleValue());
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String trimmed = data.trim();
    if ("NaN".equalsIgnoreCase(trimmed)) {
      throw Exceptions.badValueForType("BigDecimal", "NaN");
    }
    if ("Infinity".equalsIgnoreCase(trimmed) || "+Infinity".equalsIgnoreCase(trimmed)
        || "-Infinity".equalsIgnoreCase(trimmed)) {
      throw Exceptions.badValueForType("BigDecimal", trimmed);
    }
    try {
      return new BigDecimal(trimmed);
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("numeric", data, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    Number result = numericFromWire(data, offset, length);
    return result.doubleValue();
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
      throw Exceptions.cannotConvertValue("double", data, e);
    }
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return (float) decodeAsDouble(data, offset, length, type, ctx);
  }

  @Override
  public float decodeAsFloat(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return (float) decodeAsDouble(data, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    BigDecimal bd = bigDecimalFromWire(data, offset, length);
    return bd == null ? 0 : bigDecimalToInt(bd);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    return bd == null ? 0 : bigDecimalToInt(bd);
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    BigDecimal bd = bigDecimalFromWire(data, offset, length);
    return bd == null ? 0 : bigDecimalToLong(bd);
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    return bd == null ? 0 : bigDecimalToLong(bd);
  }

  private static int bigDecimalToInt(BigDecimal bd) throws SQLException {
    // Match PostgreSQL's numeric->int4 cast: round half-away-from-zero, then range-check the rounded
    // value. '2147483646.5'::int4 = 2147483647, '2147483647.4'::int4 = 2147483647 (rounds back and
    // fits), '2147483647.5'::int4 overflows (rounds to 2147483648), and '-2.5'::int4 = -3. Rounding
    // before the bounds check also matters: a check on the raw magnitude would reject 2147483647.4
    // before rounding could bring it back into range.
    BigDecimal rounded = bd.setScale(0, RoundingMode.HALF_UP);
    if (rounded.compareTo(INT_MAX_BD) > 0 || rounded.compareTo(INT_MIN_BD) < 0) {
      throw Exceptions.outOfRange(bd, "int");
    }
    return rounded.intValueExact();
  }

  private static long bigDecimalToLong(BigDecimal bd) throws SQLException {
    // Match PostgreSQL's numeric->int8 cast: round half-away-from-zero, then check the rounded value
    // against the int8 bounds exactly via BigDecimal compareTo. '9223372036854775807.9'::int8
    // overflows (rounds to 9223372036854775808), while '9223372036854775807.4'::int8 rounds back to
    // Long.MAX_VALUE and fits.
    BigDecimal rounded = bd.setScale(0, RoundingMode.HALF_UP);
    if (rounded.compareTo(LONG_MAX_BD) > 0 || rounded.compareTo(LONG_MIN_BD) < 0) {
      throw Exceptions.badValueForType("long", bd.toPlainString());
    }
    return rounded.longValueExact();
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    // Double and Float must preserve NaN / ±Infinity, which BigDecimal cannot represent, so they
    // read the value as a double instead of going through decodeBigDecimalAs.
    if (targetClass == Double.class) {
      return (T) Double.valueOf(decodeAsDouble(data, offset, length, type, ctx));
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf(decodeAsFloat(data, offset, length, type, ctx));
    }
    return decodeBigDecimalAs(decodeAsBigDecimal(data, offset, length, type, ctx), targetClass);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    // Decode straight from the text form. This previously round-tripped through
    // ByteConverter.numeric and decodeBinaryAs; the binary re-encode then re-decode was pure
    // overhead, and forcing a BigDecimal up front also rejected NaN / ±Infinity for Double/Float,
    // which the binary path accepts.
    if (targetClass == Double.class) {
      return (T) Double.valueOf(decodeAsDouble(data, type, ctx));
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf(decodeAsFloat(data, type, ctx));
    }
    return decodeBigDecimalAs(decodeAsBigDecimal(data, type, ctx), targetClass);
  }

  /**
   * Dispatches a decoded numeric value to {@code targetClass}. Shared by the text and binary
   * {@code decodeAs} paths once each has produced a {@link BigDecimal}; only Double and Float, which
   * must keep NaN / ±Infinity, are handled by the callers instead.
   */
  @SuppressWarnings("unchecked")
  private static <T> @Nullable T decodeBigDecimalAs(@Nullable BigDecimal bd, Class<T> targetClass)
      throws SQLException {
    // TODO: callers convert to BigDecimal (decodeAsBigDecimal) before reaching this target-class check,
    // so a non-finite value (NaN / +/-Infinity) with an unsupported targetClass refuses with
    // NUMERIC_VALUE_OUT_OF_RANGE rather than DATA_TYPE_MISMATCH. CoercionRoundTripSupport tolerates it as
    // a known deviation; validating the target class before the conversion would fix it. Surfaced by
    // CoercionRoundTripFuzzTest.
    if (targetClass == BigDecimal.class || targetClass == Object.class) {
      return (T) bd;
    }
    if (bd == null) {
      return null;
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(bigDecimalToLong(bd));
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf(bigDecimalToInt(bd));
    }
    if (targetClass == Short.class) {
      // Narrow through the int path so the rounding/overflow decision matches getInt and the
      // ResultSet.getShort route (decodeAsInt, then a short-range check), rather than rejecting a
      // fraction on the raw magnitude before it rounds back into range.
      int i = bigDecimalToInt(bd);
      if (i < Short.MIN_VALUE || i > Short.MAX_VALUE) {
        throw Exceptions.outOfRange(bd, "short");
      }
      return (T) Short.valueOf((short) i);
    }
    if (targetClass == Byte.class) {
      int i = bigDecimalToInt(bd);
      if (i < Byte.MIN_VALUE || i > Byte.MAX_VALUE) {
        throw Exceptions.outOfRange(bd, "byte");
      }
      return (T) Byte.valueOf((byte) i);
    }
    if (targetClass == String.class) {
      return (T) bd.toPlainString();
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(bd.compareTo(BigDecimal.ZERO) != 0);
    }
    throw Exceptions.cannotDecode("numeric", targetClass.getName());
  }

  /**
   * Decodes the binary {@code numeric} wire form, turning the unchecked exceptions
   * {@link ByteConverter#numeric} raises on a malformed buffer into a clean {@link PSQLException}.
   * A truncated or inconsistent header throws {@link IllegalArgumentException}; a header whose
   * weight/scale combination drives an internal {@code setScale} to round throws
   * {@link ArithmeticException}. Untrusted or corrupt wire bytes must surface as a checked failure
   * rather than leak either out of the decode path; the server itself never emits such bytes, so the
   * state is {@link PSQLState#DATA_ERROR}.
   */
  private static Number numericFromWire(byte[] data, int offset, int length) throws SQLException {
    try {
      return ByteConverter.numeric(data, offset, length);
    } catch (IllegalArgumentException | ArithmeticException e) {
      throw Exceptions.invalidBinaryNumericValue(e);
    }
  }

  /**
   * Returns the {@code double} sentinel when <i>value</i> is a floating-point NaN or ±Infinity —
   * values {@code numeric} supports but {@link BigDecimal} can't hold — otherwise {@code null}.
   *
   * <p>Only {@link Float} and {@link Double} are inspected: they are the sole JDK number types that
   * carry these sentinels, and probing {@link BigDecimal} via {@link BigDecimal#doubleValue()} would
   * misread a very large finite value as {@code Infinity}.</p>
   */
  private static @Nullable Double specialValue(Object value) {
    if (value instanceof Float || value instanceof Double) {
      double d = ((Number) value).doubleValue();
      if (Double.isNaN(d) || Double.isInfinite(d)) {
        return d;
      }
    }
    return null;
  }

  private static BigDecimal toBigDecimal(Object value) throws SQLException {
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
        throw Exceptions.cannotConvertValue("numeric", value, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? BigDecimal.ONE : BigDecimal.ZERO;
    }
    throw Exceptions.cannotEncode(value, "numeric");
  }
}
