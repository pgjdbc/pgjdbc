/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code uuid} values: the nil and all-ones UUIDs, an uppercase input (the server normalises it
 * to lower case, so decode must too), and a couple of typical values.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class UuidEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private UuidEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("nil", "00000000-0000-0000-0000-000000000000"));
    out.add(at("all_ones", "ffffffff-ffff-ffff-ffff-ffffffffffff"));
    out.add(at("uppercase_input", "550E8400-E29B-41D4-A716-446655440000"));
    out.add(at("typical", "550e8400-e29b-41d4-a716-446655440000"));
    out.add(at("high_bits_set", "ffffffff-0000-0000-0000-000000000001"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
