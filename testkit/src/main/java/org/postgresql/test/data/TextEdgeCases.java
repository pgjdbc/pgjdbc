/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code text} values: the empty string, multi-byte characters (accented, CJK, a supplementary-
 * plane emoji), significant whitespace, embedded newline/tab/backslash, and a long value. These stress the
 * string encoding path; none contains a single quote, so each drops into a {@code '...'::text} cast.
 *
 * <p>The value carries the same {@link String} for the bind side.
 */
public final class TextEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TextEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("empty", ""));
    out.add(at("single", "a"));
    out.add(at("accented", "café"));
    out.add(at("cjk", "中文"));
    out.add(at("emoji", "😀"));
    out.add(at("surrounding_spaces", "  leading and trailing  "));
    out.add(at("newline", "line1\nline2"));
    out.add(at("tab", "col1\tcol2"));
    out.add(at("backslash", "a\\b"));
    out.add(at("comma", "a,b"));
    out.add(at("long", repeat("a", 10000)));
    return out;
  }

  private static String repeat(String unit, int times) {
    StringBuilder sb = new StringBuilder(unit.length() * times);
    for (int i = 0; i < times; i++) {
      sb.append(unit);
    }
    return sb.toString();
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, literal);
  }
}
