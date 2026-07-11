/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code line} values ({@code {A,B,C}} for {@code Ax+By+C=0}): the axis-aligned and diagonal
 * lines, a general line, and a large coefficient. The geometric codecs were rewritten, so these exercise
 * the line decode. Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class LineEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private LineEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("diagonal", "{1,-1,0}"));
    out.add(at("horizontal", "{0,1,0}"));
    out.add(at("vertical", "{1,0,0}"));
    out.add(at("general", "{1,2,3}"));
    out.add(at("negative", "{-1,-2,-3}"));
    out.add(at("large", "{1e100,1,0}"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
