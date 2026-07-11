/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code oid} values. An {@code oid} is an unsigned 32-bit integer, so it reaches values above
 * {@code Integer.MAX_VALUE} that a signed {@code getInt} cannot hold; this catalogue covers zero, the
 * signed-int boundary, and the unsigned maximum ({@code 4294967295}).
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class OidEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private OidEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("zero", "0"));
    out.add(at("one", "1"));
    out.add(at("int_max", "2147483647"));
    out.add(at("above_int_max", "2147483648"));
    out.add(at("unsigned_max", "4294967295"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
