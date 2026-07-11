/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code int4[]} values: the empty array, single element, NULL elements, an all-NULL array, a
 * multidimensional array, and the element-value range ends. These stress array decode's dimension, NULL
 * and bounds handling.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}); the literal is the array text.
 */
public final class IntArrayEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private IntArrayEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("empty", "{}"));
    out.add(at("single", "{1}"));
    out.add(at("with_null", "{1,NULL,3}"));
    out.add(at("all_null", "{NULL}"));
    out.add(at("multidim", "{{1,2},{3,4}}"));
    out.add(at("range_ends", "{-2147483648,2147483647}"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
