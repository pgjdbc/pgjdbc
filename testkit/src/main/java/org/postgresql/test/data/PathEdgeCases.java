/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code path} values: open ({@code [...]}) and closed ({@code (...)}) paths, a single-point
 * path, and large coordinates. The open/closed flag rides in the wire form, so both are worth checking.
 * Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class PathEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private PathEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("open", "[(0,0),(1,1),(2,0)]"));
    out.add(at("closed", "((0,0),(1,1),(2,0))"));
    out.add(at("single_point", "[(0,0)]"));
    out.add(at("two_points", "[(0,0),(1,1)]"));
    out.add(at("large", "[(0,0),(1e100,1e100)]"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
