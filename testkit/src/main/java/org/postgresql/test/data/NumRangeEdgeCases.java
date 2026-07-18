/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code numrange} values. A continuous range (no canonicalisation), so the inclusive/exclusive
 * bounds are kept as given; this catalogue covers the empty range, both-unbounded, a one-sided unbounded
 * bound, an exclusive open range, and a single-point range.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class NumRangeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private NumRangeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("closed", "[1.5,2.5]"));
    out.add(at("open", "(1.5,2.5)"));
    out.add(at("empty", "empty"));
    out.add(at("both_unbounded", "(,)"));
    out.add(at("lower_unbounded", "(,2.5]"));
    out.add(at("point", "[1.5,1.5]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
