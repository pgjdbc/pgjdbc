/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code macaddr8} (8-byte EUI-64 MAC) values: all-zero, all-ones, and a typical address.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class Macaddr8EdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Macaddr8EdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("zero", "00:00:00:00:00:00:00:00"));
    out.add(at("all_ones", "ff:ff:ff:ff:ff:ff:ff:ff"));
    out.add(at("typical", "08:00:2b:01:02:03:04:05"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
