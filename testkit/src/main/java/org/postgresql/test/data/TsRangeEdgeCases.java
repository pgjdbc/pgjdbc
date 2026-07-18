/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code tsrange} values (timestamp range). The bounds are double-quoted because the timestamp
 * text contains spaces; this catalogue covers a closed range, the empty range, both-unbounded, and a range
 * whose bounds carry sub-second precision.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class TsRangeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TsRangeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("closed", "[\"2020-01-01 00:00:00\",\"2020-12-31 00:00:00\"]"));
    out.add(at("empty", "empty"));
    out.add(at("both_unbounded", "(,)"));
    out.add(at("microseconds", "[\"2020-01-01 00:00:00.000001\",\"2020-01-01 00:00:00.999999\"]"));
    out.add(at("lower_unbounded", "(,\"2020-12-31 00:00:00\"]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
