/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code box} values. PostgreSQL normalises a box to (upper-right, lower-left), so the same box
 * given corners in either order must decode identically; this catalogue gives both orders, a degenerate
 * point box, and signed/large corners. Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class BoxEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private BoxEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("low_first", "(0,0),(1,1)"));
    out.add(at("high_first", "(1,1),(0,0)"));
    out.add(at("degenerate", "(0,0),(0,0)"));
    out.add(at("negative", "(-2,-2),(-1,-1)"));
    out.add(at("large", "(0,0),(1e100,1e100)"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
