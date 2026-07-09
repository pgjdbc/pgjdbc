/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.math.BigInteger;
import java.sql.SQLException;

/**
 * Shared narrowing and widening conversions for scalar codecs whose value is an unsigned 64-bit
 * quantity represented by its raw bit pattern in a Java {@code long} ({@link Oid8Codec}'s
 * {@code oid8}, {@link Xid8Codec}'s {@code xid8}). {@link NumberDecoders} covers the same
 * conversions for a genuinely signed {@code long} ({@code int8}); the two cannot share code
 * because every bound and every out-of-range check here is against the unsigned range instead.
 */
final class UnsignedLongDecoders {

  private UnsignedLongDecoders() {
  }

  /**
   * Narrows an unsigned 64-bit bit pattern to an {@code int}, refusing rather than silently
   * truncating a value outside the unsigned 32-bit range. A value that passes returns the low 32
   * bits as an {@code int} bit pattern -- negative for a value in [2<sup>31</sup>, 2<sup>32</sup>-1),
   * matching {@link OidCodec}'s own {@code decodeAsInt} convention for the (always in-range) 32-bit
   * {@code oid}.
   */
  static int toUnsignedInt(long value) throws SQLException {
    if (value < 0 || value > 0xFFFFFFFFL) {
      throw outOfRangeForUnsigned(value, "int");
    }
    return (int) value;
  }

  /**
   * Narrows an unsigned 64-bit bit pattern to a {@code short}, refusing rather than silently
   * truncating a value outside the unsigned 16-bit range. A value that passes returns the low 16
   * bits as a {@code short} bit pattern -- negative for a value in [2<sup>15</sup>, 2<sup>16</sup>-1).
   */
  static short toUnsignedShort(long value) throws SQLException {
    if (value < 0 || value > 0xFFFFL) {
      throw outOfRangeForUnsigned(value, "short");
    }
    return (short) value;
  }

  private static PSQLException outOfRangeForUnsigned(long value, String targetType) {
    return new PSQLException(
        GT.tr("Value {0} is out of range for {1}", Long.toUnsignedString(value), targetType),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }

  /**
   * Widens an unsigned 64-bit bit pattern to a {@code double}. A plain {@code (double) value}
   * would return a negative result for a value at or above 2<sup>63</sup>.
   */
  static double toDouble(long value) {
    if (value >= 0) {
      return value;
    }
    // value is negative, i.e. its unsigned magnitude is at least 2^63. Halve it via an unsigned
    // shift (which fits in a positive long), convert, then undo the halving; this loses no more
    // precision than any other long-to-double conversion near the top of the range.
    return (double) (value >>> 1) * 2.0 + (value & 1);
  }

  /** Widens an unsigned 64-bit bit pattern to a {@link BigInteger}. */
  static BigInteger toBigInteger(long value) {
    if (value >= 0) {
      return BigInteger.valueOf(value);
    }
    return BigInteger.valueOf(value & Long.MAX_VALUE).setBit(63);
  }
}
