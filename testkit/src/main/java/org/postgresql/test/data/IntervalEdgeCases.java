/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code interval} values. PostgreSQL stores an interval as months (int32), days (int32) and
 * microseconds (int64), so this catalogue covers those magnitude limits and, more importantly, the
 * sub-second resolution boundary: intervals with exactly one microsecond, a half-microsecond, and
 * nanosecond precision, to probe how the driver rounds or truncates below the microsecond the server
 * keeps.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}): building the bind value needs the driver's
 * {@code PGInterval}. A literal that overflows the server's interval simply makes both drivers raise the
 * same error, so it stays a compatible cell rather than a false finding.
 */
public final class IntervalEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private IntervalEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("zero", "0"));
    out.add(at("one_microsecond", "00:00:00.000001"));
    out.add(at("half_microsecond", "00:00:00.0000005"));
    out.add(at("nanoseconds", "00:00:00.123456789"));
    out.add(at("one_second_minus_epsilon", "00:00:00.999999"));
    out.add(at("mixed", "1 year 2 mons 3 days 04:05:06.789"));
    out.add(at("negative_mixed", "-1 year -2 mons -3 days -04:05:06.789"));
    out.add(at("large_days", "2147483647 days"));
    out.add(at("large_years", "178000000 years"));
    out.add(at("large_negative_years", "-178000000 years"));
    out.add(at("large_time", "2562047788:00:54.775807"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
