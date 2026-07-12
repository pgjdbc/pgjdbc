/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetTime;
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
    // 24:00:00 is PostgreSQL's upper bound; LocalTime cannot represent it, so it is refused (read as
    // a string instead).
    assertThrows(SQLException.class, () -> timestampUtils.toLocalTime("24:00:00"));
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
    // 24:00:00 (86_400_000_000 micros) is a valid time the server sends, but LocalTime cannot hold it,
    // so toLocalTimeBin refuses it (read as a string instead).
    byte[] max = new byte[8];
    ByteConverter.int8(max, 0, 86_400_000_000L);
    assertThrows(SQLException.class, () -> timestampUtils.toLocalTimeBin(max));
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
    // 24:00:00 is PostgreSQL's upper bound; OffsetTime cannot represent it, so it is refused (read as
    // a string instead) rather than silently returning OffsetTime.MAX at offset -18:00.
    assertThrows(SQLException.class, () -> timestampUtils.toOffsetTime("24:00:00+01"));
  }

  private void assertToOffsetTime(String expectedOutput, String inputTime) throws SQLException {
    assertEquals(OffsetTime.parse(expectedOutput),
        timestampUtils.toOffsetTime(inputTime),
        "timestampUtils.toOffsetTime(" + inputTime + ")");
  }

  @Test
  void toTimestampRejectsEmptyString() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toTimestamp(null, ""),
        "toTimestamp(null, \"\") must reject empty input, not throw ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState of the exception for empty input");
  }

  @Test
  void toTimestampRejectsEmptyByteArray() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toTimestamp(null, new byte[0]),
        "toTimestamp(null, new byte[0]) must reject empty input, not throw ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState of the exception for empty input");
  }

  @Test
  void toOffsetDateTimeRejectsEmptyString() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toOffsetDateTime(""),
        "toOffsetDateTime(\"\") must reject empty input, not throw ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState of the exception for empty input");
  }

  @Test
  void toOffsetDateTimeRejectsEmptyByteArray() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toOffsetDateTime(new byte[0]),
        "toOffsetDateTime(new byte[0]) must reject empty input, not throw ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState of the exception for empty input");
  }

  @Test
  void toLocalTimeBinRejectsOverflowMicros() {
    // micros-of-day so large that scaling to nanoseconds overflows a long: corrupt binary time.
    final byte[] bytes = new byte[8];
    ByteConverter.int8(bytes, 0, Long.MAX_VALUE);
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toLocalTimeBin(bytes),
        "toLocalTimeBin must reject an out-of-range time with a clean SQLException,"
            + " not throw an unchecked ArithmeticException");
    assertEquals(PSQLState.DATETIME_OVERFLOW.getState(), e.getSQLState(),
        "SQLState for a time value beyond the representable range");
  }

  @Test
  void toLocalTimeBinRejectsNegativeMicros() {
    // A negative micros-of-day cannot map to a LocalTime: corrupt binary time.
    final byte[] bytes = new byte[8];
    ByteConverter.int8(bytes, 0, -1L);
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toLocalTimeBin(bytes),
        "toLocalTimeBin must reject a negative time with a clean SQLException,"
            + " not throw an unchecked DateTimeException");
    assertEquals(PSQLState.DATETIME_OVERFLOW.getState(), e.getSQLState(),
        "SQLState for a time value beyond the representable range");
  }

  @Test
  void toOffsetTimeBinRejectsOverflowMicros() {
    // micros-of-day so large that scaling to nanoseconds overflows a long: corrupt binary timetz.
    final byte[] bytes = new byte[12];
    ByteConverter.int8(bytes, 0, Long.MAX_VALUE);
    ByteConverter.int4(bytes, 8, 0);
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toOffsetTimeBin(bytes),
        "toOffsetTimeBin must reject an out-of-range timetz with a clean SQLException,"
            + " not throw an unchecked ArithmeticException");
    assertEquals(PSQLState.DATETIME_OVERFLOW.getState(), e.getSQLState(),
        "SQLState for a timetz value beyond the representable range");
  }

  @Test
  void toTimeRejectsOutOfRangeZoneOffset() {
    // A "+34" zone offset is outside the ±18:00 range java.time allows, so
    // ZoneOffset.ofHoursMinutesSeconds throws DateTimeException while parsing.
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toTime(null, "00:00:00+34"),
        "toTime must reject an out-of-range zone offset with a clean SQLException,"
            + " not throw an unchecked DateTimeException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState for a time with an out-of-range zone offset");
  }

  @Test
  void toOffsetTimeRejectsOutOfRangeHour() {
    // Hour 34 is not a valid time-of-day, so OffsetTime.of throws DateTimeException.
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toOffsetTime("34:00:00+01"),
        "toOffsetTime must reject an out-of-range hour with a clean SQLException,"
            + " not throw an unchecked DateTimeException");
    assertEquals(PSQLState.DATETIME_OVERFLOW.getState(), e.getSQLState(),
        "SQLState for a timetz with an out-of-range hour");
  }

  @Test
  void toLocalDateTimeRejectsOutOfRangeMonth() {
    // Month 13 is not a valid month, so LocalDateTime.of throws DateTimeException.
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toLocalDateTime("2024-13-01 00:00:00"),
        "toLocalDateTime must reject an out-of-range month with a clean SQLException,"
            + " not throw an unchecked DateTimeException");
    assertEquals(PSQLState.DATETIME_OVERFLOW.getState(), e.getSQLState(),
        "SQLState for a timestamp with an out-of-range month");
  }

  @Test
  void toOffsetDateTimeRejectsOutOfRangeMonth() {
    // Month 13 is not a valid month, so OffsetDateTime.of throws DateTimeException.
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toOffsetDateTime("2024-13-01 00:00:00+00"),
        "toOffsetDateTime must reject an out-of-range month with a clean SQLException,"
            + " not throw an unchecked DateTimeException");
    assertEquals(PSQLState.DATETIME_OVERFLOW.getState(), e.getSQLState(),
        "SQLState for a timestamptz with an out-of-range month");
  }

  @Test
  void toLocalDateRejectsOutOfRangeMonth() {
    // Month 13 is not a valid month, so LocalDate parsing throws DateTimeException.
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toLocalDate("2024-13-01".getBytes(StandardCharsets.UTF_8)),
        "toLocalDate must reject an out-of-range month with a clean SQLException,"
            + " not throw an unchecked DateTimeException");
    assertEquals(PSQLState.DATETIME_OVERFLOW.getState(), e.getSQLState(),
        "SQLState for a date with an out-of-range month");
  }

  @Test
  void toDateRejectsEmptyString() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toDate(null, ""),
        "toDate(null, \"\") must reject empty input, not throw ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState of the exception for empty input");
  }

  @Test
  void toDateRejectsMissingDashes() {
    // A date literal without the "yyyy-mm-dd" dashes runs the digit scan off the end of the array.
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toDate(null, "20240101"),
        "toDate must reject a date without dashes with a clean SQLException,"
            + " not throw an unchecked ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState for a date literal without the expected dashes");
  }

  @Test
  void toLocalDateRejectsEmptyByteArray() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toLocalDate(new byte[0]),
        "toLocalDate(new byte[0]) must reject empty input, not throw ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState of the exception for empty input");
  }

  @Test
  void toLocalDateRejectsMissingDashes() {
    PSQLException e = assertThrows(PSQLException.class,
        () -> timestampUtils.toLocalDate("20240101".getBytes(StandardCharsets.UTF_8)),
        "toLocalDate must reject a date without dashes with a clean SQLException,"
            + " not throw an unchecked ArrayIndexOutOfBoundsException");
    assertEquals(PSQLState.BAD_DATETIME_FORMAT.getState(), e.getSQLState(),
        "SQLState for a date literal without the expected dashes");
  }
}
