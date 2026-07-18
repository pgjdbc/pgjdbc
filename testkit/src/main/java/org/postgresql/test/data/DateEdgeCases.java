/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code date} values. PostgreSQL keeps day resolution over a 4713 BC .. 5874897 AD range and
 * has the {@code infinity}/{@code -infinity} specials, so this catalogue covers the range ends, the
 * infinities, the epoch, the Julian/Gregorian cut-over, a leap day, and the AD/BC boundary.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class DateEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private DateEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("positive_infinity", "infinity"));
    out.add(at("negative_infinity", "-infinity"));
    out.add(at("epoch", "1970-01-01"));
    out.add(at("pre_epoch", "1969-12-31"));
    out.add(at("leap_day", "2020-02-29"));
    out.add(at("first_gregorian", "1582-10-15"));
    out.add(at("last_julian", "1582-10-04"));
    out.add(at("year_one", "0001-01-01"));
    out.add(at("first_bc", "0001-12-31 BC"));
    out.add(at("far_future", "5874897-12-31"));
    out.add(at("far_past_bc", "4713-01-01 BC"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
