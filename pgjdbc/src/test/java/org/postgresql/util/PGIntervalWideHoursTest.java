/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.sql.SQLException;

/**
 * Unit tests for PGInterval hours wider than 2^31 (GitHub issue #4301).
 * These do not require a live PostgreSQL server.
 */
class PGIntervalWideHoursTest {

  /** PostgreSQL's documented maximum interval time component (hours:mins:secs.micros). */
  private static final long PG_MAX_INTERVAL_HOURS = 2562047788L;

  @Test
  void holdsHoursBeyondIntegerMax() throws SQLException {
    PGInterval interval = new PGInterval(0, 0, 0, Integer.MAX_VALUE + 1L, 0, 0);
    assertEquals(Integer.MAX_VALUE + 1L, interval.getHours());
    assertEquals((Integer.MAX_VALUE + 1L) + " hours", interval.getValue());
  }

  @Test
  void parsesPostgresMaxIntervalTime() throws SQLException {
    // Same literal as in issue #4301 reproduce steps
    PGInterval interval = new PGInterval("2562047788:00:54.775807");
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHours());
    assertEquals(0, interval.getMinutes());
    assertEquals(54.775807, interval.getSeconds(), 0.0000001);
  }

  @Test
  void parsesVerboseHoursBeyondIntegerMax() throws SQLException {
    PGInterval interval = new PGInterval("2562047788 hours");
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHours());
    assertEquals(PG_MAX_INTERVAL_HOURS + " hours", interval.getValue());
  }

  @Test
  void parsesIntegerMinHoursOnTextPath() throws SQLException {
    // Previously failed on text path: sign stripped then magnitude 2147483648 overflowed int
    PGInterval interval = new PGInterval("-2147483648:00:00");
    assertEquals(Integer.MIN_VALUE, interval.getHours());
    assertEquals(0, interval.getMinutes());
    assertEquals(0.0, interval.getSeconds(), 0.0);
  }

  @Test
  void parsesNegativeHoursBeyondIntegerMinMagnitude() throws SQLException {
    PGInterval interval = new PGInterval("-2147483649:00:00");
    assertEquals(-2147483649L, interval.getHours());
  }

  @Test
  void roundTripsWideHoursViaGetValue() throws SQLException {
    long[] samples = {
        Integer.MAX_VALUE + 1L,
        Integer.MIN_VALUE - 1L,
        PG_MAX_INTERVAL_HOURS,
        -PG_MAX_INTERVAL_HOURS,
        2147483648L,
        -2147483648L,
    };
    for (long hours : samples) {
      PGInterval original = new PGInterval(0, 0, 0, hours, 0, 0);
      PGInterval copy = new PGInterval(original.getValue());
      assertEquals(original, copy, () -> "hours=" + hours);
      assertEquals(hours, copy.getHours());
    }
  }

  @ParameterizedTest
  @CsvSource({
      "2147483647:00:00, 2147483647",
      "2147483648:00:00, 2147483648",
      "-2147483647:00:00, -2147483647",
      "-2147483648:00:00, -2147483648",
      "2562047788:00:00, 2562047788",
      "-2562047788:00:00, -2562047788",
  })
  void parsesColonHoursLiterals(String literal, long expectedHours) throws SQLException {
    PGInterval interval = new PGInterval(literal);
    assertEquals(expectedHours, interval.getHours());
  }

  @Test
  void parsesIso8601TimeHoursBeyondIntegerMax() throws SQLException {
    PGInterval interval = new PGInterval("PT2562047788H");
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHours());
  }

  @Test
  void setHoursAcceptsLong() {
    PGInterval interval = new PGInterval();
    interval.setHours(PG_MAX_INTERVAL_HOURS);
    assertEquals(PG_MAX_INTERVAL_HOURS, interval.getHours());
    interval.setHours(-PG_MAX_INTERVAL_HOURS);
    assertEquals(-PG_MAX_INTERVAL_HOURS, interval.getHours());
  }

  @Test
  void equalsAndHashCodeUseLongHours() throws SQLException {
    PGInterval a = new PGInterval(0, 0, 0, PG_MAX_INTERVAL_HOURS, 0, 0);
    PGInterval b = new PGInterval(PG_MAX_INTERVAL_HOURS + " hours");
    PGInterval c = new PGInterval(0, 0, 0, PG_MAX_INTERVAL_HOURS - 1, 0, 0);

    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(a, c);
  }

  @Test
  void addCombinesWideHours() {
    PGInterval base = new PGInterval();
    base.setHours(Integer.MAX_VALUE);
    PGInterval delta = new PGInterval();
    delta.setHours(10);
    delta.add(base);
    assertEquals(Integer.MAX_VALUE + 10L, base.getHours());
  }

  @Test
  void scaleWideHours() {
    PGInterval interval = new PGInterval();
    interval.setHours(1_000_000_000L);
    interval.scale(2);
    assertEquals(2_000_000_000L, interval.getHours());
  }
}
