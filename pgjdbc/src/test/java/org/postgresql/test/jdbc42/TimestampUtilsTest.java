/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.jdbc.TimestampUtils;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.util.TimeZone;

class TimestampUtilsTest {
  private TimestampUtils timestampUtils;

  @BeforeEach
  void setUp() {
    timestampUtils = new TimestampUtils(true, TimeZone::getDefault);
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
}
