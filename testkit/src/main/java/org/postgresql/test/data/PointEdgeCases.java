/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code point} values: the origin, signed and fractional coordinates, and a very large
 * magnitude. The geometric codecs were rewritten, so these exercise the point decode's coordinate
 * handling.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class PointEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private PointEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("origin", "(0,0)"));
    out.add(at("unit", "(1,2)"));
    out.add(at("negative", "(-1.5,-2.5)"));
    out.add(at("fractional", "(1.5,2.5)"));
    out.add(at("large", "(1e100,1e100)"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
