/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code float8} (double precision) values: the specials ({@code NaN}, {@code Infinity}, {@code
 * -Infinity}), signed zero, {@code x.5} rounding points, values around the {@code int} and {@code long}
 * boundaries (to probe {@code getInt}/{@code getLong} coercion), finite values outside the {@code float4}
 * range (to probe {@code getFloat} overflow/underflow refusal), and the magnitude extremes of the type.
 */
public final class Float8EdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Float8EdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("nan", "NaN"));
    out.add(at("positive_infinity", "Infinity"));
    out.add(at("negative_infinity", "-Infinity"));
    out.add(at("zero", "0"));
    out.add(at("negative_zero", "-0"));
    out.add(at("one", "1"));
    out.add(at("minus_one", "-1"));
    out.add(at("half", "0.5"));
    out.add(at("two_and_half", "2.5"));
    out.add(at("near_int_max", "2147483647"));
    out.add(at("above_int_max", "2147483648"));
    out.add(at("near_long_max", "9223372036854775807"));
    out.add(at("above_long_max", "9223372036854775808"));
    // Finite values outside float4 range: getFloat must refuse (overflow to +/-Infinity, or a nonzero
    // value that underflows to zero), matching PostgreSQL's float8->float4 cast.
    out.add(at("overflows_float4", "1e300"));
    out.add(at("negative_overflows_float4", "-1e300"));
    out.add(at("underflows_float4", "1e-300"));
    out.add(at("largest", "1.7976931348623157e308"));
    out.add(at("smallest_subnormal", "5e-324"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, Double.valueOf(Double.parseDouble(literal)));
  }
}
