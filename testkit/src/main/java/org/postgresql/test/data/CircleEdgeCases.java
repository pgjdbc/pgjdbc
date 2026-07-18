/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code circle} values ({@code <(x,y),r>}): a unit circle, a zero-radius (point) circle, a
 * signed centre, and a large radius. Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class CircleEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private CircleEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("unit", "<(0,0),1>"));
    out.add(at("zero_radius", "<(0,0),0>"));
    out.add(at("negative_centre", "<(-1.5,-2.5),3>"));
    out.add(at("large_radius", "<(0,0),1e100>"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
