/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A shared catalogue of {@code numeric} edge-case values for driver tests.
 *
 * <p>It gathers the values that stress {@code numeric} decode, encode and coercion: the special values
 * ({@code NaN}, {@code Infinity}, {@code -Infinity}), precision and scale extremes, binary-format group
 * boundaries, and -- for probing rounding and overflow in {@code getByte}/{@code getShort}/{@code
 * getInt}/{@code getLong} -- values sitting exactly on and just around each integer type's minimum and
 * maximum (including the {@code x.5} half-way points that decide whether a coercion rounds past the
 * boundary).
 *
 * <p>Meant to be reused across modules -- the differential backward-compat oracle, fuzzers, and ordinary
 * coercion tests -- so the same edge cases are exercised everywhere.
 */
public final class NumericEdgeCases {
  private static final BigDecimal ONE = BigDecimal.ONE;
  private static final BigDecimal HALF = new BigDecimal("0.5");
  private static final BigDecimal FOUR_TENTHS = new BigDecimal("0.4");

  /** {@code NaN} and the infinities: valid {@code numeric} values with no {@link BigDecimal} form. */
  public static final List<EdgeCase> SPECIAL = Collections.unmodifiableList(specials());

  /** Zero variants, sign, trailing zeros: the everyday shapes that still catch scale bugs. */
  public static final List<EdgeCase> BASIC = Collections.unmodifiableList(basics());

  /**
   * Precision and scale extremes, binary-format (base-10000) group boundaries, and values just
   * outside the {@code float4} / {@code float8} range that a narrowing read must refuse.
   */
  public static final List<EdgeCase> PRECISION = Collections.unmodifiableList(precision());

  /** Values on and around each integer type's min/max, including {@code x.5} rounding points. */
  public static final List<EdgeCase> INTEGER_BOUNDARIES =
      Collections.unmodifiableList(integerBoundaries());

  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  /** Lookup by {@link EdgeCase#name()}. */
  public static final Map<String, EdgeCase> BY_NAME = Collections.unmodifiableMap(byName());

  private NumericEdgeCases() {
  }

  private static List<EdgeCase> specials() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(new EdgeCase("nan", "NaN", null));
    out.add(new EdgeCase("positive_infinity", "Infinity", null));
    out.add(new EdgeCase("negative_infinity", "-Infinity", null));
    return out;
  }

  private static List<EdgeCase> basics() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("zero", BigDecimal.ZERO));
    out.add(at("zero_scaled", new BigDecimal("0.00")));
    out.add(at("one", BigDecimal.ONE));
    out.add(at("minus_one", new BigDecimal("-1")));
    out.add(at("trailing_zeros", new BigDecimal("12345.6700")));
    return out;
  }

  private static List<EdgeCase> precision() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("tiny", new BigDecimal("0.00000000000000000001")));
    out.add(at("high_precision_integer", new BigDecimal("12345678901234567890123456789012345678")));
    out.add(at("high_precision_mixed", new BigDecimal("12345678901234567890.12345678901234567890")));
    // Binary numeric is stored in base-10000 groups; these sit on the group and weight boundaries.
    out.add(at("group_boundary_fraction", new BigDecimal("0.0001")));
    out.add(at("group_boundary_10000", new BigDecimal("10000")));
    out.add(at("just_below_group", new BigDecimal("9999.9999")));
    // Floating-point range boundaries: values just outside float4's range (both signs), so a
    // numeric->float4 read must refuse -- overflow past Float.MAX_VALUE (~3.4e38) to +/-Infinity, and a
    // nonzero value below the smallest subnormal float (~1.4e-45) that underflows to 0 -- and just
    // outside float8's range, so a numeric->float8 read overflows past Double.MAX_VALUE (~1.8e308).
    // All stay comfortably within numeric's own precision.
    out.add(at("float4_overflow_positive", new BigDecimal("1e39")));
    out.add(at("float4_overflow_negative", new BigDecimal("-1e39")));
    out.add(at("float4_underflow_positive", new BigDecimal("1e-46")));
    out.add(at("float4_underflow_negative", new BigDecimal("-1e-46")));
    out.add(at("float8_overflow_positive", new BigDecimal("1e309")));
    out.add(at("float8_overflow_negative", new BigDecimal("-1e309")));
    return out;
  }

  private static List<EdgeCase> integerBoundaries() {
    List<EdgeCase> out = new ArrayList<>();
    addBoundary(out, "byte", new BigDecimal("-128"), new BigDecimal("127"));
    addBoundary(out, "short", new BigDecimal("-32768"), new BigDecimal("32767"));
    addBoundary(out, "int", new BigDecimal("-2147483648"), new BigDecimal("2147483647"));
    addBoundary(out, "long",
        new BigDecimal("-9223372036854775808"), new BigDecimal("9223372036854775807"));
    return out;
  }

  private static void addBoundary(List<EdgeCase> out, String label, BigDecimal min, BigDecimal max) {
    // Around the maximum: exact, one past (clear overflow), and the fractional points that decide
    // whether a coercion rounds up over the boundary.
    out.add(at(label + "_max", max));
    out.add(at(label + "_max_plus_1", max.add(ONE)));
    out.add(at(label + "_max_plus_0_4", max.add(FOUR_TENTHS)));
    out.add(at(label + "_max_plus_0_5", max.add(HALF)));
    out.add(at(label + "_max_minus_0_5", max.subtract(HALF)));
    // Around the minimum, mirrored (rounding away from zero pushes past the boundary).
    out.add(at(label + "_min", min));
    out.add(at(label + "_min_minus_1", min.subtract(ONE)));
    out.add(at(label + "_min_minus_0_4", min.subtract(FOUR_TENTHS)));
    out.add(at(label + "_min_minus_0_5", min.subtract(HALF)));
    out.add(at(label + "_min_plus_0_5", min.add(HALF)));
  }

  private static EdgeCase at(String name, BigDecimal value) {
    return new EdgeCase(name, value.toPlainString(), value);
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.addAll(SPECIAL);
    out.addAll(BASIC);
    out.addAll(PRECISION);
    out.addAll(INTEGER_BOUNDARIES);
    return out;
  }

  private static Map<String, EdgeCase> byName() {
    Map<String, EdgeCase> out = new LinkedHashMap<>();
    for (EdgeCase c : all()) {
      out.put(c.name(), c);
    }
    return out;
  }
}
