/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code timetz} (time with time zone) values. On top of the {@code time} sub-second boundary,
 * this catalogue covers the UTC-offset extremes PostgreSQL accepts (+14:00 .. -12:00), a half-hour
 * offset, and the {@code 24:00:00} upper bound, since the offset is carried through decode.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class TimeTzEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private TimeTzEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("utc", "00:00:00+00"));
    out.add(at("max_offset", "12:00:00+14"));
    out.add(at("min_offset", "12:00:00-12"));
    out.add(at("half_hour_offset", "12:34:56.123456+05:30"));
    out.add(at("nanoseconds", "12:34:56.123456789+03"));
    out.add(at("one_microsecond", "00:00:00.000001+00"));
    out.add(at("half_microsecond", "00:00:00.0000005+00"));
    out.add(at("end_of_day", "24:00:00+00"));
    out.add(at("second_boundary", "23:59:59.999999-08"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
