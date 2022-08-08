/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.postgresql.jdbc.TimestampUtils;

import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetTime;

public class TimestampUtilsTest {
  private TimestampUtils timestampUtils;

  @Before
  public void setUp() {
    timestampUtils = createTimestampUtils();
  }

  @Test
  public void testToStringOfLocalTime() {
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
        "timestampUtils.toString(LocalTime.parse(" + inputTime + "))"
            + (message == null ? ": " + message : ""),
        expectedOutput,
        timestampUtils.toString(LocalTime.parse(inputTime)));
  }

  @Test
  public void testToLocalTime() throws SQLException {
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

  @Test
  public void testLocalDateTimeRounding() {

    assertLocalDateTimeRounding("500 ns rounds up to the next micro", "2022-01-04T08:57:13.123457", "2022-01-04T08:57:13.123456500");
    assertLocalDateTimeRounding("2022-01-04T08:57:13.123456", "2022-01-04T08:57:13.123456499");
    assertLocalDateTimeRounding("2022-01-04T08:57:14","2022-01-04T08:57:13.999999501");

    LocalDateTime unrounded = LocalDateTime.parse("2022-01-04T08:57:13.123456");
    assertSame(
        "nanosecond part is 000, so timestampUtils.round should not modify the value, and keep the same object as result for performance reasons",
        unrounded,
        timestampUtils.round(unrounded)
    );
  }

  private void assertLocalDateTimeRounding(String message, String expected, String toRound) {
    assertEquals(message + " in timestampUtils.round(LocalDateTime.parse(" + toRound + "))", LocalDateTime.parse(expected), timestampUtils.round(LocalDateTime.parse(toRound)));
  }

  private void assertToLocalTime(String expectedOutput, String inputTime, String message) throws SQLException {
    assertEquals(
        "timestampUtils.toLocalTime(" + inputTime + ")"
            + (message == null ? ": " + message : ""),
        LocalTime.parse(expectedOutput),
        timestampUtils.toLocalTime(inputTime));
  }

  @Test
  public void testToStringOfOffsetTime() {
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
    assertEquals("timestampUtils.toString(OffsetTime.parse(" + inputTime + "))",
        expectedOutput,
        timestampUtils.toString(OffsetTime.parse(inputTime)));
  }

  @Test
  public void testToOffsetTime() throws SQLException {
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
    assertEquals("timestampUtils.toOffsetTime(" + inputTime + ")",
        OffsetTime.parse(expectedOutput),
        timestampUtils.toOffsetTime(inputTime));
  }
}
