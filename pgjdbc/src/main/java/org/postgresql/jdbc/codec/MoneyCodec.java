/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PGmoney;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;

/**
 * Codec for the PostgreSQL {@code money} type.
 *
 * <p>{@code money} (a.k.a. {@code cash}) is a 64-bit count of the currency's minor unit (cents). The
 * number of fraction digits ({@code frac_digits}), the currency symbol and the grouping separators
 * are all {@code lc_monetary} properties; none of them travels over the wire protocol
 * ({@code frac_digits} is not even a {@code GUC}). The driver therefore cannot rebuild the server's
 * localized rendering (for example {@code "$1.00"} or {@code "-$1.00"}) from the raw binary int64,
 * so it keeps {@code money} on the <em>text</em> receive format: {@link #decodesBinary()}
 * returns {@code false}, so the type is requested in text, the server formats it per its locale, and
 * this codec only strips the currency decoration to recover the numeric value. This matches the
 * historical driver, which never listed {@code money} among the binary-transfer OIDs.</p>
 *
 * <p>The binary methods below decode the 8-byte int64 anyway, for the cases where the driver cannot
 * avoid binary — {@code money} nested inside a binary {@code record} or array, or a caller that has
 * explicitly opted {@code money} into binary transfer — assuming the near-universal scale of 2.</p>
 *
 * <p>{@code getObject} on a {@code money} column returns a {@link Double} (money maps to
 * {@link java.sql.Types#DOUBLE}), matching the legacy contract. The {@link PGmoney} object form is
 * produced only for an explicit {@code getObject(int, PGmoney.class)} / {@code getObject(typeName)}
 * request; the {@code money} to {@link PGmoney} entry in the Java type registry drives those paths,
 * not plain {@code getObject}.</p>
 */
public final class MoneyCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder, ArrayElementCodec {

  public static final MoneyCodec INSTANCE = new MoneyCodec();

  /** Encode/bind stays on the legacy fallback path; only decode is money-aware. */
  private static final FallbackCodec SCALAR = FallbackCodec.INSTANCE;

  /**
   * Fraction digits assumed when decoding the binary form. This is the locale's {@code frac_digits}
   * ({@code lc_monetary}), which the protocol does not carry; 2 is near-universal. Only the defensive
   * binary path (see the class comment) depends on it — text values arrive already formatted.
   */
  private static final int FRACTION_DIGITS = 2;

  private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);

  private MoneyCodec() {
  }

  @Override
  public String getTypeName() {
    return "money";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Double.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return MoneyArrayLeafCodec.INSTANCE;
  }

  @Override
  public boolean decodesBinary() {
    // Keep money on text receive: the server renders it per lc_monetary and the driver cannot
    // reconstruct that string (symbol, grouping, frac_digits) from the raw int64. See the class
    // comment.
    return false;
  }

  // ----------------------------------------------------------------- text decode

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseMoney(data).doubleValue();
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) {
    // The server already produced the locale-formatted literal ("$1.00"); getString returns it as is.
    return data;
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toInt(parseMoney(data));
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toLong(parseMoney(data));
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return (float) parseMoney(data).doubleValue();
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseMoney(data).doubleValue();
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return parseMoney(data);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (isMoneyObjectTarget(targetClass)) {
      // Build PGmoney from the parsed numeric value rather than its string constructor, which does
      // not understand the negative locale forms ("-$1.00", "($1.00)").
      return targetClass.cast(new PGmoney(parseMoney(data).doubleValue()));
    }
    if (targetClass == String.class) {
      return targetClass.cast(data);
    }
    return decodeDecimalAs(parseMoney(data), targetClass);
  }

  // --------------------------------------------------------------- binary decode

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return decimalOf(cents(data, offset, length)).doubleValue();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Cannot reproduce the locale rendering (symbol/grouping) offline, so emit a plain decimal. This
    // path is defensive — money is text-only on receive (see the class comment).
    return decimalOf(cents(data, offset, length)).toPlainString();
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return toInt(decimalOf(cents(data, offset, length)));
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return toLong(decimalOf(cents(data, offset, length)));
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return (float) decimalOf(cents(data, offset, length)).doubleValue();
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decimalOf(cents(data, offset, length)).doubleValue();
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return decimalOf(cents(data, offset, length));
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    BigDecimal value = decimalOf(cents(data, offset, length));
    if (isMoneyObjectTarget(targetClass)) {
      return targetClass.cast(new PGmoney(value.doubleValue()));
    }
    if (targetClass == String.class) {
      return targetClass.cast(value.toPlainString());
    }
    return decodeDecimalAs(value, targetClass);
  }

  // ------------------------------------------------------------------- encode (legacy fallback)

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return SCALAR.encodeBinary(value, type, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return SCALAR.encodeText(value, type, ctx);
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return SCALAR.canEncodeBinary(value, type, ctx);
  }

  // ------------------------------------------------------------------------ helpers

  /** Whether {@code targetClass} asks for the {@code money} object form ({@link PGmoney}). */
  private static boolean isMoneyObjectTarget(Class<?> targetClass) {
    return targetClass == PGmoney.class || targetClass == PGobject.class;
  }

  /**
   * Boxes a decoded money {@code value} as {@code targetClass}. {@code Object} resolves to
   * {@link Double} (the legacy {@code getObject} type); the integral targets carry the whole-unit
   * value, truncated toward zero and range-checked, matching the legacy string conversions.
   */
  @SuppressWarnings("unchecked")
  private static <T> T decodeDecimalAs(BigDecimal value, Class<T> targetClass) throws SQLException {
    if (targetClass == Double.class || targetClass == Object.class) {
      return (T) Double.valueOf(value.doubleValue());
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf(value.floatValue());
    }
    if (targetClass == BigDecimal.class) {
      return (T) value;
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(toLong(value));
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf(toInt(value));
    }
    if (targetClass == Short.class) {
      return (T) Short.valueOf((short) Exceptions.checkIntRange(toLong(value), "short"));
    }
    if (targetClass == String.class) {
      return (T) value.toPlainString();
    }
    throw Exceptions.cannotDecode("money", targetClass.getName());
  }

  /** Reads the binary int64 count of minor units. */
  private static long cents(byte[] data, int offset, int length) throws SQLException {
    if (length != 8) {
      throw Exceptions.invalidBinaryLength("money", length);
    }
    return ByteConverter.int8(data, offset);
  }

  /** Exact decimal value of a minor-unit count, scaled by the assumed {@link #FRACTION_DIGITS}. */
  private static BigDecimal decimalOf(long cents) {
    return BigDecimal.valueOf(cents, FRACTION_DIGITS);
  }

  private static long toLong(BigDecimal value) throws SQLException {
    // Truncate toward zero, then range-check, matching the legacy PgResultSet.toLong fallback.
    BigInteger units = value.toBigInteger();
    if (units.compareTo(LONG_MAX) > 0 || units.compareTo(LONG_MIN) < 0) {
      throw Exceptions.outOfRange(value, "long");
    }
    return units.longValue();
  }

  private static int toInt(BigDecimal value) throws SQLException {
    return Exceptions.checkIntRange(toLong(value), "int");
  }

  /**
   * Parses a server-rendered {@code money} literal into its exact decimal value, independent of the
   * locale that produced it.
   *
   * <p>The value is negative when it is wrapped in parentheses ({@code "($1.00)"}) or carries a minus
   * sign ({@code "-$1.00"}, {@code "$-1.00"}, {@code "1,00-"}): the currency symbol and digits never
   * contain {@code '('} or {@code '-'}, so either marks a negative amount. The currency symbol,
   * whitespace and sign are then dropped, leaving the digits and the {@code ','}/{@code '.'}
   * separators.</p>
   *
   * <p>PostgreSQL renders {@code money} with the locale's fraction digits, so the <em>rightmost</em>
   * separator is the decimal point and every earlier separator groups the integer part. Choosing the
   * decimal separator by position rather than by character means the driver does not need to know
   * whether the locale uses {@code ','} or {@code '.'} for the decimal point ({@code "1,234.56"} and
   * {@code "1.234,56"} both parse to {@code 1234.56}), and never has to consult {@code lc_monetary}.
   * The one rendering this cannot disambiguate is a zero-fraction-digit currency written with grouping
   * (for example {@code "1,234,567"}), which is ambiguous from the text alone.</p>
   */
  private static BigDecimal parseMoney(CharSequence data) throws SQLException {
    int length = data.length();
    boolean negative = false;

    // Keep only the digits and separators, dropping the currency symbol, whitespace, sign and parens;
    // a '(' or '-' anywhere marks a negative amount (folded into this scan instead of two indexOf
    // passes, so a borrowed CharArraySequence slice is parsed without a String copy).
    StringBuilder tokens = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      char c = data.charAt(i);
      if (c >= '0' && c <= '9' || c == '.' || c == ',') {
        tokens.append(c);
      } else if (c == '(' || c == '-') {
        negative = true;
      }
    }

    int decimalPos = Math.max(tokens.lastIndexOf("."), tokens.lastIndexOf(","));
    StringBuilder number = new StringBuilder(tokens.length() + 1);
    if (negative) {
      number.append('-');
    }
    for (int i = 0; i < tokens.length(); i++) {
      char c = tokens.charAt(i);
      if (c >= '0' && c <= '9') {
        number.append(c);
      } else if (i == decimalPos) {
        number.append('.');
      }
      // else: a grouping separator — dropped.
    }
    if (number.length() == 0 || (negative && number.length() == 1)) {
      throw Exceptions.badValueForType("money", data);
    }
    try {
      return new BigDecimal(number.toString());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("money", data, e);
    }
  }
}
