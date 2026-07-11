/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

class PGIntervalTest {

  @Test
  void parsesSqlStandardCompoundInterval() throws SQLException {
    PGInterval interval = new PGInterval("+1-2 +3 +4:05:06");

    assertEquals(1, interval.getYears());
    assertEquals(2, interval.getMonths());
    assertEquals(3, interval.getDays());
    assertEquals(4, interval.getHours());
    assertEquals(5, interval.getMinutes());
    assertEquals(6, interval.getSeconds());
  }

  @Test
  void parsesSqlStandardYearMonthInterval() throws SQLException {
    PGInterval interval = new PGInterval("1-2");

    assertEquals(1, interval.getYears());
    assertEquals(2, interval.getMonths());
  }

  @Test
  void appliesSqlStandardLeadingSignToYearAndMonth() throws SQLException {
    PGInterval interval = new PGInterval("-1-2");

    assertEquals(-1, interval.getYears());
    assertEquals(-2, interval.getMonths());
  }

  @Test
  void parsesSqlStandardDayTimeInterval() throws SQLException {
    PGInterval interval = new PGInterval("1 2:03:04");

    assertEquals(1, interval.getDays());
    assertEquals(2, interval.getHours());
    assertEquals(3, interval.getMinutes());
    assertEquals(4, interval.getSeconds());
  }

  @Test
  void parsesSqlStandardDayTimeIntervalWithMixedSigns() throws SQLException {
    PGInterval interval = new PGInterval("-1 +2:03:04");

    assertEquals(-1, interval.getDays());
    assertEquals(2, interval.getHours());
    assertEquals(3, interval.getMinutes());
    assertEquals(4, interval.getSeconds());
  }

  @Test
  void appliesSqlStandardLeadingSignToAllDayTimeFields() throws SQLException {
    PGInterval interval = new PGInterval("-1 2:03:04.5");

    assertEquals(-1, interval.getDays());
    assertEquals(-2, interval.getHours());
    assertEquals(-3, interval.getMinutes());
    assertEquals(-4.5, interval.getSeconds());
  }

  @Test
  void parsesSqlStandardFullySignedIntervals() throws SQLException {
    assertInterval(new PGInterval("+0-0 +1 -1:00:00"), 0, 0, 1, -1, 0, 0);
    assertInterval(new PGInterval("-1-2 -3 -4:05:06.7"), -1, -2, -3, -4, -5, -6.7);
    assertInterval(new PGInterval("-0-10 +1 +23:45:12.34"), 0, -10, 1, 23, 45, 12.34);
  }

  @Test
  void preservesZeroAndTimeOnlyIntervalFormats() throws SQLException {
    assertInterval(new PGInterval("0"), 0, 0, 0, 0, 0, 0);
    assertInterval(new PGInterval("4:05:06.7"), 0, 0, 0, 4, 5, 6.7);
    assertInterval(new PGInterval("-0:00:00.1"), 0, 0, 0, 0, 0, -0.1);
    assertInterval(new PGInterval("15:57"), 0, 0, 0, 15, 57, 0);
  }

  @Test
  void rejectsMalformedSqlStandardInterval() {
    assertThrows(SQLException.class, () -> new PGInterval("1-"));
  }

  @Test
  void rejectsMalformedSqlStandardDayTimeInterval() {
    assertThrows(SQLException.class, () -> new PGInterval("1 2:3"));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "+1-2 +3 +4:05",
      "+1-2 3 +4:05:06",
      "+1-2 +3 4:05:06"
  })
  void rejectsMalformedSqlStandardCompoundInterval(String value) {
    assertThrows(SQLException.class, () -> new PGInterval(value));
  }

  private static void assertInterval(PGInterval interval, int years, int months, int days,
      int hours, int minutes, double seconds) {
    assertEquals(years, interval.getYears());
    assertEquals(months, interval.getMonths());
    assertEquals(days, interval.getDays());
    assertEquals(hours, interval.getHours());
    assertEquals(minutes, interval.getMinutes());
    assertEquals(seconds, interval.getSeconds());
  }
}
