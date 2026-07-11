/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code timestamp} (without time zone) values. PostgreSQL keeps microsecond resolution over a
 * 4713 BC .. 294276 AD range and has the special {@code infinity}/{@code -infinity} values, so this
 * catalogue covers the range ends, the infinities, the epoch, and the sub-second boundary: one
 * microsecond, a half-microsecond, and nanosecond precision, to probe how the driver rounds or truncates
 * below the microsecond the server keeps (java.sql.Timestamp carries nanoseconds, so the two disagree
 * exactly here).
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class TimestampEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TimestampEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("positive_infinity", "infinity"));
    out.add(at("negative_infinity", "-infinity"));
    out.add(at("epoch", "1970-01-01 00:00:00"));
    out.add(at("one_microsecond", "2020-01-01 00:00:00.000001"));
    out.add(at("half_microsecond", "2020-01-01 00:00:00.0000005"));
    out.add(at("nanoseconds", "2020-01-01 00:00:00.123456789"));
    out.add(at("second_boundary", "2020-01-01 00:00:00.999999"));
    out.add(at("pre_epoch", "1969-12-31 23:59:59.999999"));
    out.add(at("far_future", "294276-12-31 23:59:59.999999"));
    out.add(at("far_past_bc", "4713-01-01 00:00:00 BC"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
