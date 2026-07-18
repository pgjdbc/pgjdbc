/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code text[]} values: the empty array, NULL elements, an empty-string element (distinct from
 * NULL), a quoted element containing a comma, and a multidimensional array. These stress array decode's
 * quoting, NULL-vs-empty and dimension handling.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}); the literal is the array text.
 */
public final class TextArrayEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TextArrayEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("empty", "{}"));
    out.add(at("simple", "{a,b,c}"));
    out.add(at("with_null", "{a,NULL,c}"));
    out.add(at("empty_string_element", "{\"\"}"));
    out.add(at("quoted_comma", "{\"a,b\",c}"));
    out.add(at("multidim", "{{a,b},{c,d}}"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
