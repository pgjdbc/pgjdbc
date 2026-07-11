/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code daterange} values. A discrete range like {@code int4range}, so PostgreSQL canonicalises
 * the bounds ({@code [d1,d2]} becomes {@code [d1,d2+1)}); this catalogue covers the empty range,
 * both-unbounded, a single-day range, and the inclusive/half-open forms.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class DateRangeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private DateRangeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("half_open", "[2020-01-01,2020-12-31)"));
    out.add(at("inclusive", "[2020-01-01,2020-12-31]"));
    out.add(at("empty", "empty"));
    out.add(at("both_unbounded", "(,)"));
    out.add(at("single_day", "[2020-01-01,2020-01-01]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
