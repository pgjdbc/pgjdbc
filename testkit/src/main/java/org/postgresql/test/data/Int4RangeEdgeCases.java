/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code int4range} values. A discrete range, so PostgreSQL canonicalises the bounds ({@code
 * [1,10]} becomes {@code [1,11)}); this catalogue covers the empty range, both-unbounded, single-value,
 * one-sided unbounded, the inclusive/exclusive bound combinations, and the full type range.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class Int4RangeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Int4RangeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("inclusive", "[1,10]"));
    out.add(at("exclusive", "(1,10)"));
    out.add(at("half_open", "[1,10)"));
    out.add(at("empty", "empty"));
    out.add(at("both_unbounded", "(,)"));
    out.add(at("lower_unbounded", "(,10)"));
    out.add(at("upper_unbounded", "[1,)"));
    out.add(at("single", "[5,5]"));
    out.add(at("full", "[-2147483648,2147483647]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
