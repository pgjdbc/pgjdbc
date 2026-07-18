/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code time} (without time zone) values. PostgreSQL keeps microsecond resolution over
 * 00:00:00 .. 24:00:00 (it accepts the {@code 24:00:00} upper bound), so this catalogue covers the two
 * bounds and the sub-second boundary: one microsecond, a half-microsecond, and nanosecond precision, to
 * probe how the driver rounds or truncates below the microsecond the server keeps.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class TimeEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TimeEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("midnight", "00:00:00"));
    out.add(at("end_of_day", "24:00:00"));
    out.add(at("noon", "12:00:00"));
    out.add(at("one_microsecond", "00:00:00.000001"));
    out.add(at("half_microsecond", "00:00:00.0000005"));
    out.add(at("nanoseconds", "12:34:56.123456789"));
    out.add(at("second_boundary", "23:59:59.999999"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
