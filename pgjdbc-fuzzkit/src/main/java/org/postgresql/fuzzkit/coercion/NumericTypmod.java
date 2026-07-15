/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

/**
 * The PostgreSQL {@code numeric} type modifier, packed the way the backend packs {@code numeric(p,s)}.
 * A modifier carries a precision {@code p} (1..1000) and a scale {@code s}; since PostgreSQL 15 the
 * scale may be negative or exceed the precision (roughly -1000..1000). The fuzzers stamp such a
 * modifier onto a descriptor with {@link ScalarDescriptor#withTypmod(int)} to drive the
 * {@code NumericCodec} rescale path, and predict the rescaled scale with {@link #scaleOf(int)}.
 *
 * <p>This mirrors the backend layout independently of the driver's own decoder: the modifier is
 * {@code ((p << 16) | (s & 0xffff)) + VARHDRSZ}, and the scale is a sign-extended 11-bit field below
 * the {@code VARHDRSZ} offset. Keeping the formula here, rather than calling the codec, lets the codec
 * fuzzers use it as an independent oracle.
 */
public final class NumericTypmod {

  /** {@code VARHDRSZ}: the 4-byte length header PostgreSQL adds to every packed type modifier. */
  private static final int VARHDRSZ = 4;

  private NumericTypmod() {
  }

  /**
   * The packed modifier for {@code numeric(precision, scale)}.
   *
   * @param precision the declared precision (number of significant digits)
   * @param scale the declared scale (digits after the decimal point; may be negative on PG15+)
   * @return the packed type modifier
   */
  public static int of(int precision, int scale) {
    return ((precision << 16) | (scale & 0xffff)) + VARHDRSZ;
  }

  /**
   * The scale a {@code numeric} value carries after this modifier is applied -- the scale the
   * {@code NumericCodec} rescales to. Sign-extends the 11-bit scale field, so {@code of(2, -2)} yields
   * {@code -2}.
   *
   * @param typmod a packed {@code numeric} modifier, or {@code -1} for none
   * @return the applied scale, or {@code -1} when no modifier applies
   */
  public static int scaleOf(int typmod) {
    if (typmod == -1) {
      return -1;
    }
    return (((typmod - VARHDRSZ) & 0x7ff) ^ 0x400) - 0x400;
  }
}
