/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code money} values. PostgreSQL stores {@code money} as a 64-bit count of the currency's
 * minor unit (cents), so this catalogue covers zero, the smallest unit, signs, and the int64 range ends
 * (${@code 92233720368547758.07} down to −${@code 92233720368547758.08}).
 *
 * <p>The text rendering is locale-dependent ({@code lc_monetary}); the differential oracle reads both
 * drivers under the same session, so a difference still isolates a driver change. Read-only ({@link
 * EdgeCase#value()} is {@code null}).
 */
public final class MoneyEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private MoneyEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("zero", "0.00"));
    out.add(at("one_cent", "0.01"));
    out.add(at("one", "1.00"));
    out.add(at("minus_one", "-1.00"));
    out.add(at("max", "92233720368547758.07"));
    out.add(at("min", "-92233720368547758.08"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
