/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.util.ByteConverter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.TimeZone;

class TimestampUtilsTest {
  private TimestampUtils timestampUtils;

  @BeforeEach
  void setUp() {
    timestampUtils = new TimestampUtils(false, TimeZone::getDefault);
  }

  @Test
  void toStringOfLocalTime() {
    assertToStringOfLocalTime("00:00:00");
    assertToStringOfLocalTime("00:00:00.1");
    assertToStringOfLocalTime("00:00:00.12");
    assertToStringOfLocalTime("00:00:00.123");
    assertToStringOfLocalTime("00:00:00.1234");
    assertToStringOfLocalTime("00:00:00.12345");
    assertToStringOfLocalTime("00:00:00.123456");

    assertToStringOfLocalTime("00:00:00.999999");
    assertToStringOfLocalTime("00:00:00.999999", "00:00:00.999999499", "499 NanoSeconds round down");
    assertToStringOfLocalTime("00:00:01", "00:00:00.999999500", "500 NanoSeconds round up");

    assertToStringOfLocalTime("23:59:59");

    assertToStringOfLocalTime("23:59:59.999999");
    assertToStringOfLocalTime("23:59:59.999999", "23:59:59.999999499", "499 NanoSeconds round down");
    assertToStringOfLocalTime("24:00:00", "23:59:59.999999500", "500 NanoSeconds round up");
    assertToStringOfLocalTime("24:00:00", "23:59:59.999999999", "999 NanoSeconds round up");
  }

  private void assertToStringOfLocalTime(String inputTime) {
    assertToStringOfLocalTime(inputTime, inputTime, null);
  }

  private void assertToStringOfLocalTime(String expectedOutput, String inputTime, String message) {
    assertEquals(
        expectedOutput,
        timestampUtils.toString(LocalTime.parse(inputTime)),
        "timestampUtils.toString(LocalTime.parse(" + inputTime + "))"
            + (message == null ? ": " + message : ""));
  }

  @Test
  void toLocalTime() throws SQLException {
    assertToLocalTime("00:00:00");

    assertToLocalTime("00:00:00.1");
    assertToLocalTime("00:00:00.12");
    assertToLocalTime("00:00:00.123");
    assertToLocalTime("00:00:00.1234");
    assertToLocalTime("00:00:00.12345");
    assertToLocalTime("00:00:00.123456");
    assertToLocalTime("00:00:00.999999");

    assertToLocalTime("23:59:59");
    assertToLocalTime("23:59:59.999999"); // 0 NanoSeconds
    assertToLocalTime("23:59:59.9999999"); // 900 NanoSeconds
    assertToLocalTime("23:59:59.99999999"); // 990 NanoSeconds
    assertToLocalTime("23:59:59.999999998"); // 998 NanoSeconds
    assertToLocalTime(LocalTime.MAX.toString(), "24:00:00", "LocalTime can't represent 24:00:00");
  }

  private void assertToLocalTime(String inputTime) throws SQLException {
    assertToLocalTime(inputTime, inputTime, null);
  }

  private void assertToLocalTime(String expectedOutput, String inputTime, String message) throws SQLException {
    assertEquals(
        LocalTime.parse(expectedOutput),
        timestampUtils.toLocalTime(inputTime),
        "timestampUtils.toLocalTime(" + inputTime + ")"
            + (message == null ? ": " + message : ""));
  }

  @Test
  void toLocalTimeBin() throws SQLException {
    assertToLocalTimeBin("00:00:00", 0L);

    assertToLocalTimeBin("00:00:00.1", 100_000L);
    assertToLocalTimeBin("00:00:00.12", 120_000L);
    assertToLocalTimeBin("00:00:00.123", 123_000L);
    assertToLocalTimeBin("00:00:00.1234", 123_400L);
    assertToLocalTimeBin("00:00:00.12345", 123_450L);
    assertToLocalTimeBin("00:00:00.123456", 123_456L);
    assertToLocalTimeBin("00:00:00.999999", 999_999L);

    assertToLocalTimeBin("23:59:59", 86_399_000_000L);
    assertToLocalTimeBin("23:59:59.999999", 86_399_999_999L);
    assertToLocalTimeBin(LocalTime.MAX.toString(), 86_400_000_000L, "LocalTime can't represent 24:00:00");
  }

  private void assertToLocalTimeBin(String expectedOutput, long inputMicros) throws SQLException {
    assertToLocalTimeBin(expectedOutput, inputMicros, null);
  }

  private void assertToLocalTimeBin(String expectedOutput, long inputMicros, String message) throws SQLException {
    final byte[] bytes = new byte[8];
    ByteConverter.int8(bytes, 0, inputMicros);
    assertEquals(
        LocalTime.parse(expectedOutput),
        timestampUtils.toLocalTimeBin(bytes),
        "timestampUtils.toLocalTime(" + inputMicros + ")"
        + (message == null ? ": " + message : ""));
  }

  @Test
  void toStringOfOffsetTime() {
    assertToStringOfOffsetTime("00:00:00+00", "00:00:00+00:00");
    assertToStringOfOffsetTime("00:00:00.1+01", "00:00:00.1+01:00");
    assertToStringOfOffsetTime("00:00:00.12+12", "00:00:00.12+12:00");
    assertToStringOfOffsetTime("00:00:00.123-01", "00:00:00.123-01:00");
    assertToStringOfOffsetTime("00:00:00.1234-02", "00:00:00.1234-02:00");
    assertToStringOfOffsetTime("00:00:00.12345-12", "00:00:00.12345-12:00");
    assertToStringOfOffsetTime("00:00:00.123456+01:30", "00:00:00.123456+01:30");
    assertToStringOfOffsetTime("00:00:00.123456-12:34", "00:00:00.123456-12:34");

    assertToStringOfOffsetTime("23:59:59+01", "23:59:59+01:00");

    assertToStringOfOffsetTime("23:59:59.999999+01", "23:59:59.999999+01:00");
    assertToStringOfOffsetTime("23:59:59.999999+01", "23:59:59.999999499+01:00"); // 499 NanoSeconds
    assertToStringOfOffsetTime("24:00:00+01", "23:59:59.999999500+01:00"); // 500 NanoSeconds
    assertToStringOfOffsetTime("24:00:00+01", "23:59:59.999999999+01:00"); // 999 NanoSeconds
  }

  private void assertToStringOfOffsetTime(String expectedOutput, String inputTime) {
    assertEquals(expectedOutput,
        timestampUtils.toString(OffsetTime.parse(inputTime)),
        "timestampUtils.toString(OffsetTime.parse(" + inputTime + "))");
  }

  @Test
  void toOffsetTime() throws SQLException {
    assertToOffsetTime("00:00:00+00:00", "00:00:00+00");
    assertToOffsetTime("00:00:00.1+01:00", "00:00:00.1+01");
    assertToOffsetTime("00:00:00.12+12:00", "00:00:00.12+12");
    assertToOffsetTime("00:00:00.123-01:00", "00:00:00.123-01");
    assertToOffsetTime("00:00:00.1234-02:00", "00:00:00.1234-02");
    assertToOffsetTime("00:00:00.12345-12:00", "00:00:00.12345-12");
    assertToOffsetTime("00:00:00.123456+01:30", "00:00:00.123456+01:30");
    assertToOffsetTime("00:00:00.123456-12:34", "00:00:00.123456-12:34");

    assertToOffsetTime("23:59:59.999999+01:00", "23:59:59.999999+01"); // 0 NanoSeconds
    assertToOffsetTime("23:59:59.9999999+01:00", "23:59:59.9999999+01"); // 900 NanoSeconds
    assertToOffsetTime("23:59:59.99999999+01:00", "23:59:59.99999999+01"); // 990 NanoSeconds
    assertToOffsetTime("23:59:59.999999998+01:00", "23:59:59.999999998+01"); // 998 NanoSeconds
    assertToOffsetTime(OffsetTime.MAX.toString(), "24:00:00+01");
  }

  private void assertToOffsetTime(String expectedOutput, String inputTime) throws SQLException {
    assertEquals(OffsetTime.parse(expectedOutput),
        timestampUtils.toOffsetTime(inputTime),
        "timestampUtils.toOffsetTime(" + inputTime + ")");
  }

  @Test
  void getSharedCalendar() {
    Calendar calendar = timestampUtils.getSharedCalendar(null);
    // The default time zone should be applied
    assertEquals(TimeZone.getDefault(), calendar.getTimeZone());
    assertInstanceOf(GregorianCalendar.class, calendar);
    GregorianCalendar gregorianCalendar = (GregorianCalendar) calendar;
    // The returned calendar should be pure (proleptic) Gregorian
    assertEquals(new Date(Long.MIN_VALUE), gregorianCalendar.getGregorianChange());
  }

  @Test
  void createProlepticGregorianCalendar() {
    Calendar calendar = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getTimeZone(ZoneOffset.UTC));
    // The supplied time zone should be applied
    assertEquals(TimeZone.getTimeZone(ZoneOffset.UTC), calendar.getTimeZone());
    assertInstanceOf(GregorianCalendar.class, calendar);
    GregorianCalendar gregorianCalendar = (GregorianCalendar) calendar;
    // The returned calendar should be pure (proleptic) Gregorian
    assertEquals(new Date(Long.MIN_VALUE), gregorianCalendar.getGregorianChange());
    // Perform a date calculation close to the default switch from Julian to Gregorian dates
    gregorianCalendar.clear();
    gregorianCalendar.set(1582, Calendar.OCTOBER, 5);
    gregorianCalendar.add(Calendar.DAY_OF_MONTH, 15);
    assertEquals(1582, gregorianCalendar.get(Calendar.YEAR));
    assertEquals(Calendar.OCTOBER, gregorianCalendar.get(Calendar.MONTH));
    // Would be 30 if the calendar had the default Julian to Gregorian change date
    assertEquals(20, gregorianCalendar.get(Calendar.DAY_OF_MONTH));
  }

  @Test
  void toDate() throws SQLException {
    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    expectedCal.set(2025, Calendar.NOVEMBER, 25);

    assertEquals(expectedCal.getTime(), timestampUtils.toDate(TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault()), "2025-11-25"));
    assertEquals(expectedCal.getTime(), timestampUtils.toDate(null, "2025-11-25 00:00:00"));
  }

  @Test
  void toDateYear1000() throws SQLException {
    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    expectedCal.set(1000, Calendar.JANUARY, 1);
    // Be aware that Date.toString() formats with the Julian calendar for such old dates
    assertEquals(expectedCal.getTime(), timestampUtils.toDate(TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault()), "1000-01-01"));
    assertEquals(expectedCal.getTime(), timestampUtils.toDate(null, "1000-01-01 00:00:00"));
  }

  @Test
  void toDateBin() throws SQLException {
    final int days = 10;
    final byte[] bytes = new byte[4];
    ByteConverter.int4(bytes, 0, days);

    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    // Postgres epoch (but in default time zone) + days
    expectedCal.set(2000, Calendar.JANUARY, 1 + days);

    assertEquals(expectedCal.getTime(), timestampUtils.toDateBin(null, bytes));
  }

  @Test
  void toDateBinYear1000() throws SQLException {
    // java.time is based on ISO-8601, that means the proleptic Gregorian calendar is used
    final int days = Math.toIntExact(ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1), LocalDate.of(1000, 1, 1)));
    final byte[] bytes = new byte[4];
    ByteConverter.int4(bytes, 0, days);

    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    expectedCal.set(1000, Calendar.JANUARY, 1);

    // Be aware that Date.toString() formats with the Julian calendar for such old dates
    assertEquals(expectedCal.getTime(), timestampUtils.toDateBin(null, bytes));
  }

  @Test
  void toTimestamp() throws SQLException {
    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    expectedCal.set(2025, Calendar.NOVEMBER, 25, 16, 34, 45);

    assertEquals(expectedCal.getTime().getTime(), timestampUtils.toTimestamp(TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault()), "2025-11-25 16:34:45").getTime());
    assertEquals(expectedCal.getTime().getTime(), timestampUtils.toTimestamp(null, "2025-11-25 16:34:45").getTime());
  }

  @Test
  void toTimestampYear1000() throws SQLException {
    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    expectedCal.set(1000, Calendar.NOVEMBER, 25, 16, 34, 45);

    assertEquals(expectedCal.getTime().getTime(), timestampUtils.toTimestamp(TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault()), "1000-11-25 16:34:45").getTime());
    assertEquals(expectedCal.getTime().getTime(), timestampUtils.toTimestamp(null, "1000-11-25 16:34:45").getTime());
  }

  @Test
  void toTimestampBin() throws SQLException {
    final int days = 10;
    final int hours = 14;
    final int minutes = 16;
    final long daysInMicros = days * 24L * 60L * 60L * 1000_000L;
    final long hoursInMicros = hours * 60L * 60L * 1000_000L;
    final long minutesInMicros = minutes * 60L * 1000_000L;
    final byte[] bytes = new byte[8];
    ByteConverter.int8(bytes, 0, daysInMicros + hoursInMicros + minutesInMicros);

    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    // Postgres epoch (but in default time zone) + days
    expectedCal.set(2000, Calendar.JANUARY, 1 + days, hours, minutes);

    assertEquals(expectedCal.getTime().getTime(), timestampUtils.toTimestampBin(null, bytes, false).getTime());
  }

  @Test
  void toTimestampBinYear1000() throws SQLException {
    // java.time is based on ISO-8601, that means the proleptic Gregorian calendar is used
    final int days = Math.toIntExact(ChronoUnit.DAYS.between(LocalDate.of(2000, 1, 1), LocalDate.of(1000, 1, 1)));
    final int hours = 14;
    final int minutes = 16;
    final long daysInMicros = days * 24L * 60L * 60L * 1000_000L;
    final long hoursInMicros = hours * 60L * 60L * 1000_000L;
    final long minutesInMicros = minutes * 60L * 1000_000L;
    final byte[] bytes = new byte[8];
    ByteConverter.int8(bytes, 0, daysInMicros + hoursInMicros + minutesInMicros);

    Calendar expectedCal = TimestampUtils.createProlepticGregorianCalendar(TimeZone.getDefault());
    expectedCal.clear();
    expectedCal.set(1000, Calendar.JANUARY, 1, hours, minutes);

    assertEquals(expectedCal.getTime().getTime(), timestampUtils.toTimestampBin(null, bytes, false).getTime());
  }

}
