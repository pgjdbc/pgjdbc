/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

/**
 * Regression coverage for issue #4294. The SQL-standard literals come from PostgreSQL
 * {@code interval_out} with {@code IntervalStyle=sql_standard}, except compatibility cases.
 */
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
    assertEquals(-4.5, interval.getSeconds(), 0.0000001);
  }

  @Test
  void parsesSqlStandardFullySignedIntervals() throws SQLException {
    assertInterval(new PGInterval("+0-0 +1 -1:00:00"), 0, 0, 1, -1, 0, 0);
    assertInterval(new PGInterval("-1-2 -3 -4:05:06.7"), -1, -2, -3, -4, -5, -6.7);
    assertInterval(new PGInterval("-0-10 +1 +23:45:12.34"), 0, -10, 1, 23, 45, 12.34);
  }

  @Test
  void preservesExistingIntervalFormats() throws SQLException {
    assertInterval(new PGInterval("1 year 2 mons 3 days 04:05:06"), 1, 2, 3, 4, 5, 6);
    assertInterval(new PGInterval("@ 1 year 2 mons ago"), -1, -2, 0, 0, 0, 0);
    assertInterval(new PGInterval("P1Y2M3DT4H5M6S"), 1, 2, 3, 4, 5, 6);
    assertInterval(new PGInterval("0"), 0, 0, 0, 0, 0, 0);
    assertInterval(new PGInterval("4:05:06.7"), 0, 0, 0, 4, 5, 6.7);
    assertInterval(new PGInterval("-0:00:00.1"), 0, 0, 0, 0, 0, -0.1);
    assertInterval(new PGInterval("15:57"), 0, 0, 0, 15, 57, 0);
  }

  @Test
  void rejectsMalformedSqlStandardInterval() {
    PSQLException error = assertThrows(PSQLException.class, () -> new PGInterval("1-"));

    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), error.getSQLState());
  }

  @Test
  void rejectsMalformedSqlStandardDayTimeInterval() {
    assertThrows(SQLException.class, () -> new PGInterval("1 2:3"));
  }

  @Test
  void rejectsOutOfRangeSqlStandardInterval() {
    String value = "999999999999999999999-1";

    PSQLException error = assertThrows(PSQLException.class, () -> new PGInterval(value));

    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), error.getSQLState());
    assertInstanceOf(NumberFormatException.class, error.getCause());
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

  @Test
  void parsesSqlStandardDayTimeWithoutSeconds() throws SQLException {
    // Match the seconds-less time format accepted for pre-7.4 server compatibility.
    assertInterval(new PGInterval("1 2:03"), 0, 0, 1, 2, 3, 0);
    assertInterval(new PGInterval("-1 2:03"), 0, 0, -1, -2, -3, 0);
    assertInterval(new PGInterval("-1 +2:03"), 0, 0, -1, 2, 3, 0);
  }

  @Test
  void preservesSqlStandardMicrosecondPrecision() throws SQLException {
    PGInterval interval = new PGInterval("+0-0 +0 +0:00:00.123456");

    assertInterval(interval, 0, 0, 0, 0, 0, 0.123456);
    assertEquals(0, interval.getWholeSeconds());
    assertEquals(123456, interval.getMicroSeconds());
  }

  @Test
  void roundTripsSqlStandardIntervalThroughGetValue() throws SQLException {
    PGInterval interval = new PGInterval("-1-2 +3 -4:05:06.123456");

    assertInterval(new PGInterval(interval.getValue()), -1, -2, 3, -4, -5, -6.123456);
  }

  @Test
  void rejectsOutOfRangeSqlStandardCompoundHours() {
    PSQLException error = assertThrows(PSQLException.class,
        () -> new PGInterval("+0-0 +0 +2147483648:00:00"));

    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), error.getSQLState());
    assertInstanceOf(NumberFormatException.class, error.getCause());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2562047788015 2:03:04", "1 2147483648:00:00"})
  void rejectsSqlStandardIntervalOutsidePgIntervalRange(String value) {
    PSQLException error = assertThrows(PSQLException.class,
        () -> new PGInterval(value));

    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), error.getSQLState());
    assertInstanceOf(NumberFormatException.class, error.getCause());
  }

  @Test
  void rejectsOutOfRangePostgresIntervalWithTheSameSqlState() {
    PSQLException error = assertThrows(PSQLException.class,
        () -> new PGInterval("2147483648 years"));

    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), error.getSQLState());
    assertInstanceOf(NumberFormatException.class, error.getCause());
  }

  private static void assertInterval(PGInterval interval, int years, int months, int days,
      int hours, int minutes, double seconds) {
    assertEquals(years, interval.getYears());
    assertEquals(months, interval.getMonths());
    assertEquals(days, interval.getDays());
    assertEquals(hours, interval.getHours());
    assertEquals(minutes, interval.getMinutes());
    assertEquals(seconds, interval.getSeconds(), 0.0000001);
  }
}
