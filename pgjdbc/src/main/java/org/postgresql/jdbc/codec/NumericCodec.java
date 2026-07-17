/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Encoding;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.NumberParser;

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
  public String getPrimaryTypeName() {
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
    BigDecimal bd = result instanceof BigDecimal
        ? (BigDecimal) result
        : BigDecimal.valueOf(result.doubleValue());
    // getObject rescales to the column's declared scale (e.g. the negative scale of numeric(2,-2)),
    // which the wire dscale alone does not carry. getBigDecimal (decodeAsBigDecimal) stays
    // wire-faithful, so the rescale lives here rather than in the shared bigDecimalFromWire.
    return applyTypmodScale(bd, type.getTypmod());
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
    // decodeAsBigDecimal applies the descriptor's modifier, so getObject on a text numeric rescales.
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

  // getString must render numeric the way the server does. The default decodeAsString goes through
  // BigDecimal.toString(), which switches to scientific notation once the adjusted exponent drops
  // below -6 (e.g. 1E-20), but PostgreSQL's numeric ::text never does that and the text protocol's
  // getString returns the plain server text. toPlainString() keeps binary getString consistent with
  // the text path, the server, and decodeTextAs(String.class). NaN / +/-Infinity keep their spelling.

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return plainString(decodeBinary(data, offset, length, type, ctx));
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return plainString(decodeText(data, type, ctx));
  }

  private static @Nullable String plainString(@Nullable Object decoded) {
    if (decoded == null) {
      return null;
    }
    if (decoded instanceof BigDecimal) {
      return ((BigDecimal) decoded).toPlainString();
    }
    double d = ((Number) decoded).doubleValue();
    if (Double.isNaN(d)) {
      return "NaN";
    }
    return d > 0 ? "Infinity" : "-Infinity";
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    BigDecimal bd = bigDecimalFromWire(data, offset, length);
    // Honour the descriptor's applied modifier so a numeric(p,s)[] element decodes to its declared
    // scale. A plain column's getBigDecimal stays wire-faithful because the ResultSet hands this a
    // descriptor without a modifier; only a stamped one (an array element) rescales.
    return bd == null ? null : applyTypmodScale(bd, type.getTypmod());
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
  public @Nullable BigDecimal decodeAsBigDecimal(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String trimmed = data.toString().trim();
    if ("NaN".equalsIgnoreCase(trimmed)) {
      throw Exceptions.badValueForType("BigDecimal", "NaN");
    }
    if ("Infinity".equalsIgnoreCase(trimmed) || "+Infinity".equalsIgnoreCase(trimmed)
        || "-Infinity".equalsIgnoreCase(trimmed)) {
      throw Exceptions.badValueForType("BigDecimal", trimmed);
    }
    try {
      return applyTypmodScale(new BigDecimal(trimmed), type.getTypmod());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("numeric", trimmed, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    Number result = numericFromWire(data, offset, length);
    double d = result.doubleValue();
    // A finite numeric whose magnitude exceeds the double range overflows to +/-Infinity; refuse it
    // rather than saturate, matching PostgreSQL's numeric->float8 cast. A genuine NaN / +/-Infinity is
    // a Double sentinel (numericFromWire never returns a BigDecimal for those), so it passes through.
    if (result instanceof BigDecimal && Double.isInfinite(d)) {
      throw Exceptions.outOfRange(result, "double");
    }
    return d;
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String trimmed = data.toString().trim();
    if ("NaN".equalsIgnoreCase(trimmed)) {
      return Double.NaN;
    }
    if ("Infinity".equalsIgnoreCase(trimmed) || "+Infinity".equalsIgnoreCase(trimmed)) {
      return Double.POSITIVE_INFINITY;
    }
    if ("-Infinity".equalsIgnoreCase(trimmed)) {
      return Double.NEGATIVE_INFINITY;
    }
    double d;
    try {
      d = Double.parseDouble(trimmed);
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("double", trimmed, e);
    }
    // The special spellings are handled above, so an infinite result here is a finite value that
    // overflowed the double range; refuse it, matching numeric->float8.
    if (Double.isInfinite(d)) {
      throw Exceptions.outOfRange(trimmed, "double");
    }
    return d;
  }

  // float narrows through decodeAsDouble and NumberDecoders.doubleToFloat, which refuses a finite
  // value that overflows to +/-Infinity or a nonzero value that underflows to 0 (matching
  // numeric->float4) while letting a genuine NaN / +/-Infinity through.

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return NumberDecoders.doubleToFloat(decodeAsDouble(data, offset, length, type, ctx));
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return NumberDecoders.doubleToFloat(decodeAsDouble(data, type, ctx));
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    BigDecimal bd = bigDecimalFromWire(data, offset, length);
    return bd == null ? 0 : bigDecimalToInt(bd);
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    BigDecimal bd = decodeAsBigDecimal(data, type, ctx);
    return bd == null ? 0 : bigDecimalToLong(bd);
  }

  // getInt/getLong on a text numeric otherwise decode the wire bytes to a String and build a
  // BigDecimal just to round it. When the value has no fractional part -- a bare optional sign
  // followed by ASCII digits -- the digits parse straight off the bytes with no String or
  // BigDecimal. A fractional or special value ('.', an exponent, NaN/Infinity) is not eligible:
  // getFastLong truncates the fraction toward zero, whereas numeric->int rounds half-away-from-zero
  // (see bigDecimalToInt), so those fall back to the BigDecimal path, which rounds correctly.

  @Override
  public int decodeTextBytesAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (isPlainIntegerAscii(data) && Encoding.hasAsciiNumbers(ctx.getCharset())) {
      try {
        return (int) NumberParser.getFastLong(data, Integer.MIN_VALUE, Integer.MAX_VALUE);
      } catch (NumberFormatException ignored) {
        // Out of int range; fall back to the BigDecimal path, which throws the range error.
      }
    }
    return decodeAsInt(new String(data, ctx.getCharset()), type, ctx);
  }

  @Override
  public long decodeTextBytesAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (isPlainIntegerAscii(data) && Encoding.hasAsciiNumbers(ctx.getCharset())) {
      try {
        return NumberParser.getFastLong(data, Long.MIN_VALUE, Long.MAX_VALUE);
      } catch (NumberFormatException ignored) {
        // Out of long range; fall back to the BigDecimal path, which throws the range error.
      }
    }
    return decodeAsLong(new String(data, ctx.getCharset()), type, ctx);
  }

  /**
   * Reports whether {@code data} is a plain integer in ASCII: an optional leading {@code '-'} then
   * at least one ASCII digit and nothing else. A {@code '.'}, an exponent, or a NaN/Infinity literal
   * makes it ineligible for the {@link NumberParser#getFastLong(byte[], long, long)} byte fast path,
   * which would truncate a fraction the numeric-to-integer cast is required to round.
   */
  private static boolean isPlainIntegerAscii(byte[] data) {
    int len = data.length;
    int start = len > 0 && data[0] == '-' ? 1 : 0;
    if (start == len) {
      return false; // empty, or a bare "-"
    }
    for (int i = start; i < len; i++) {
      byte b = data[i];
      if (b < '0' || b > '9') {
        return false;
      }
    }
    return true;
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
   * Rescales {@code bd} to the scale encoded in {@code typmod}, or returns it unchanged when no
   * modifier applies ({@code typmod == -1}). This reproduces the {@code getObject} contract, where a
   * {@code numeric(p,s)} value carries the column's declared scale — including a negative scale such
   * as {@code numeric(2,-2)} — that the wire {@code dscale} alone does not convey. Rounds
   * {@link RoundingMode#HALF_EVEN}, matching the legacy {@code PgResultSet} rescale.
   */
  private static BigDecimal applyTypmodScale(BigDecimal bd, int typmod) {
    if (typmod == -1) {
      return bd;
    }
    return bd.setScale(decodeNumericScale(typmod), RoundingMode.HALF_EVEN);
  }

  /**
   * Extracts the (possibly negative) scale from a {@code numeric} type modifier. Mirrors PostgreSQL's
   * numeric typmod layout: after removing the {@code VARHDRSZ} offset, the low 11 bits hold the scale
   * as a sign-extended value, so {@code numeric(2,-2)} yields {@code -2}.
   */
  private static int decodeNumericScale(int typmod) {
    return ((((typmod - 4) & 0x7ff) ^ 0x400) - 0x400);
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
