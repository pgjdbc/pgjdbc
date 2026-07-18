/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code polygon} values: a square, a triangle, a single-vertex polygon, and large coordinates.
 * Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class PolygonEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private PolygonEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("square", "((0,0),(1,0),(1,1),(0,1))"));
    out.add(at("triangle", "((0,0),(1,0),(0,1))"));
    out.add(at("single_vertex", "((0,0))"));
    out.add(at("negative", "((-1,-1),(-1,1),(1,1),(1,-1))"));
    out.add(at("large", "((0,0),(1e100,0),(0,1e100))"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
