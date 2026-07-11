/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Shared {@code getObject(Class)} coercions for the integral and floating-point codecs.
 *
 * <p>{@link Int2Codec}, {@link Int4Codec} and {@link Int8Codec} decode to a {@code long} and box it
 * through {@link #decodeIntegralAs}; {@link Float4Codec} and {@link Float8Codec} decode to a
 * {@code double} and share {@link #decodeFloatingAs}. The per-type boxing, range checks and error
 * messages live here once rather than being copied into each codec.</p>
 *
 * <p>Each codec resolves its own natural type and {@code Object.class} directly before delegating, so
 * the common {@code getObject(column)} path boxes without widening. Type-specific targets are handled
 * by the codec too — the {@code String} form (float- or double-specific), {@code int8}'s
 * {@link java.math.BigInteger}, and the {@code Long} bound (which differs between {@code float4} and
 * {@code float8}) — so none of those reach these methods.</p>
 */
final class NumberDecoders {

  // A double cannot represent Long.MAX_VALUE exactly: the nearest double is 2^63, one past the
  // range. So the fitting test is a half-open interval [-2^63, 2^63) and the upper bound is
  // exclusive. This matches PostgreSQL's FLOAT8_FITS_IN_INT64 and the driver's historical behaviour.
  private static final double LONG_MIN_DOUBLE = Long.MIN_VALUE;
  private static final double LONG_MAX_DOUBLE = Long.MAX_VALUE;

  private NumberDecoders() {
  }

  /**
   * Narrows a floating-point {@code value} to {@code int}, throwing if it is NaN, infinite, or
   * outside the {@code int} range. Rounds to the nearest integer, ties to even, then range-checks the
   * rounded value, matching PostgreSQL's {@code float8->int4} cast (which uses C {@code rint}).
   *
   * @param value the floating-point value to narrow
   * @return {@code value} rounded to the nearest {@code int}
   * @throws SQLException if {@code value} does not fit in {@code int}
   */
  static int floatingToInt(double value) throws SQLException {
    double rounded = Math.rint(value);
    if (Double.isNaN(rounded) || rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
      throw outOfRangeForInt(value);
    }
    return (int) rounded;
  }

  /**
   * Narrows a floating-point {@code value} to {@code short}, throwing if it is NaN, infinite, or
   * outside the {@code short} range. Rounds to the nearest integer, ties to even, matching
   * PostgreSQL's {@code float8->int2} cast.
   *
   * @param value the floating-point value to narrow
   * @return {@code value} rounded to the nearest {@code short}
   * @throws SQLException if {@code value} does not fit in {@code short}
   */
  static short floatingToShort(double value) throws SQLException {
    double rounded = Math.rint(value);
    if (Double.isNaN(rounded) || rounded < Short.MIN_VALUE || rounded > Short.MAX_VALUE) {
      throw outOfRangeForShort(value);
    }
    return (short) rounded;
  }

  /**
   * Narrows a floating-point {@code value} to {@code byte}, throwing if it is NaN, infinite, or
   * outside the {@code byte} range. Rounds to the nearest integer, ties to even, consistent with the
   * other floating-point integer narrowings ({@code byte} has no dedicated server cast).
   *
   * @param value the floating-point value to narrow
   * @return {@code value} rounded to the nearest {@code byte}
   * @throws SQLException if {@code value} does not fit in {@code byte}
   */
  static byte floatingToByte(double value) throws SQLException {
    double rounded = Math.rint(value);
    if (Double.isNaN(rounded) || rounded < Byte.MIN_VALUE || rounded > Byte.MAX_VALUE) {
      throw outOfRangeForByte(value);
    }
    return (byte) rounded;
  }

  /**
   * Narrows a floating-point {@code value} to {@code long}, throwing if it is NaN, infinite, or
   * outside the {@code long} range. Rounds to the nearest integer, ties to even, then range-checks the
   * rounded value, matching PostgreSQL's {@code float8->int8} cast.
   *
   * @param value the floating-point value to narrow
   * @return {@code value} rounded to the nearest {@code long}
   * @throws SQLException if {@code value} does not fit in {@code long}
   */
  static long floatingToLong(double value) throws SQLException {
    double rounded = Math.rint(value);
    if (Double.isNaN(rounded) || rounded < LONG_MIN_DOUBLE || rounded >= LONG_MAX_DOUBLE) {
      throw Exceptions.outOfRange(value, "long");
    }
    return (long) rounded;
  }

  /**
   * Boxes an integral {@code value} as {@code targetClass}; {@code typeName} names the source type in
   * the "cannot decode" error.
   */
  @SuppressWarnings("unchecked")
  static <T> T decodeIntegralAs(long value, Class<T> targetClass, String typeName)
      throws SQLException {
    if (targetClass == Long.class) {
      return (T) Long.valueOf(value);
    }
    if (targetClass == Integer.class) {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw outOfRangeForInt(value);
      }
      return (T) Integer.valueOf((int) value);
    }
    if (targetClass == Short.class) {
      if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
        throw outOfRangeForShort(value);
      }
      return (T) Short.valueOf((short) value);
    }
    if (targetClass == Byte.class) {
      if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
        throw outOfRangeForByte(value);
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
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw Exceptions.cannotDecode(typeName, targetClass.getName());
  }

  /**
   * Boxes a floating-point {@code value} as {@code targetClass}; {@code typeName} names the source
   * type in the "cannot decode" error.
   */
  @SuppressWarnings("unchecked")
  static <T> T decodeFloatingAs(double value, Class<T> targetClass, String typeName)
      throws SQLException {
    if (targetClass == Double.class) {
      return (T) Double.valueOf(value);
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf((float) value);
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf(floatingToInt(value));
    }
    if (targetClass == Short.class) {
      return (T) Short.valueOf(floatingToShort(value));
    }
    if (targetClass == Byte.class) {
      return (T) Byte.valueOf(floatingToByte(value));
    }
    if (targetClass == BigDecimal.class) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        throw Exceptions.cannotConvertToBigDecimal(value);
      }
      return (T) BigDecimal.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw Exceptions.cannotDecode(typeName, targetClass.getName());
  }

  private static SQLException outOfRangeForInt(Object value) {
    return Exceptions.outOfRange(value, "int");
  }

  private static SQLException outOfRangeForShort(Object value) {
    return Exceptions.outOfRange(value, "short");
  }

  private static SQLException outOfRangeForByte(Object value) {
    return Exceptions.outOfRange(value, "byte");
  }
}
