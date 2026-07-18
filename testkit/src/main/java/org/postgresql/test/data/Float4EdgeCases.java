/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code float4} (real) values: the specials ({@code NaN}, {@code Infinity}, {@code -Infinity}),
 * signed zero, {@code x.5} rounding points, values around the {@code int} boundary (to probe {@code
 * getInt}/{@code getLong} coercion), and the magnitude extremes of the type.
 */
public final class Float4EdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Float4EdgeCases() {
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
    out.add(at("largest", "3.4e38"));
    out.add(at("smallest_normal", "1.2e-38"));
    // The exact float4 extremes: the largest finite value and the smallest positive subnormal. They
    // sit just inside the range, so getFloat must accept them -- the boundary complement of the
    // float8 overflows_float4/underflows_float4 cases that must refuse.
    out.add(at("float4_max", "3.4028235e38"));
    out.add(at("smallest_subnormal", "1.4e-45"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, Float.valueOf(Float.parseFloat(literal)));
  }
}
