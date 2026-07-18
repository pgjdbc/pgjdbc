/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code timestamptz} values. Like {@code timestamp} it keeps microsecond resolution over a
 * 4713 BC .. 294276 AD range with {@code infinity}/{@code -infinity} specials, and additionally carries a
 * UTC offset, so this catalogue covers the range ends, the infinities, the epoch, the sub-second boundary
 * (one microsecond, a half-microsecond, nanosecond precision) and the offset extremes (+14:00 .. -12:00).
 *
 * <p>The rendered value depends on the JVM/session time zone; the differential oracle reads both drivers
 * under the same zone, so a difference still isolates a driver change rather than a zone difference.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class TimestampTzEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TimestampTzEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("positive_infinity", "infinity"));
    out.add(at("negative_infinity", "-infinity"));
    out.add(at("epoch", "1970-01-01 00:00:00+00"));
    out.add(at("one_microsecond", "2020-01-01 00:00:00.000001+00"));
    out.add(at("half_microsecond", "2020-01-01 00:00:00.0000005+00"));
    out.add(at("nanoseconds", "2020-01-01 00:00:00.123456789+05:30"));
    out.add(at("max_offset", "2020-01-01 12:00:00+14"));
    out.add(at("min_offset", "2020-01-01 12:00:00-12"));
    out.add(at("far_future", "294276-12-31 23:59:59.999999+00"));
    out.add(at("far_past_bc", "4713-01-01 00:00:00+00 BC"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
