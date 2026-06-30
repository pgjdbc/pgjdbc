/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

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

  private NumberDecoders() {
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
    throw Codec.cannotDecode(typeName, targetClass.getName());
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
    if (targetClass == BigDecimal.class) {
      if (Double.isNaN(value) || Double.isInfinite(value)) {
        throw new PSQLException(
            GT.tr("Cannot convert {0} to BigDecimal", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) BigDecimal.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw Codec.cannotDecode(typeName, targetClass.getName());
  }

  private static PSQLException outOfRangeForInt(Object value) {
    return new PSQLException(
        GT.tr("Value {0} is out of range for int", value),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }

  private static PSQLException outOfRangeForShort(Object value) {
    return new PSQLException(
        GT.tr("Value {0} is out of range for short", value),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }

  private static PSQLException outOfRangeForByte(Object value) {
    return new PSQLException(
        GT.tr("Value {0} is out of range for byte", value),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }
}
