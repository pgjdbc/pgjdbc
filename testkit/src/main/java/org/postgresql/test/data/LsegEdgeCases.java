/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code lseg} (line segment) values: a unit segment, a degenerate zero-length segment, signed
 * and large endpoints. Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class LsegEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private LsegEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("unit", "[(0,0),(1,1)]"));
    out.add(at("degenerate", "[(0,0),(0,0)]"));
    out.add(at("horizontal", "[(0,0),(5,0)]"));
    out.add(at("negative", "[(-1.5,-2.5),(3,4)]"));
    out.add(at("large", "[(0,0),(1e100,1e100)]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
