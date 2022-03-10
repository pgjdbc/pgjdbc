/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;

import org.postgresql.jdbc.TimestampUtils;

import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.util.TimeZone;

public class TimestampUtilsTest {
  private TimestampUtils timestampUtils;

  @Before
  public void setUp() {
    timestampUtils = new TimestampUtils(true, TimeZone::getDefault);
  }

  @Test
  public void testToStringOfLocalTime() {
    assertToStringOfLocalTime("00:00:00", "00:00:00");
    assertToStringOfLocalTime("00:00:00.1", "00:00:00.1");
    assertToStringOfLocalTime("00:00:00.12", "00:00:00.12");
    assertToStringOfLocalTime("00:00:00.123", "00:00:00.123");
    assertToStringOfLocalTime("00:00:00.1234", "00:00:00.1234");
    assertToStringOfLocalTime("00:00:00.12345", "00:00:00.12345");
    assertToStringOfLocalTime("00:00:00.123456", "00:00:00.123456");

    assertToStringOfLocalTime("00:00:00.999999", "00:00:00.999999");
    assertToStringOfLocalTime("00:00:00.999999", "00:00:00.999999499");
    assertToStringOfLocalTime("00:00:01", "00:00:00.999999500");

    assertToStringOfLocalTime("23:59:59", "23:59:59");

    assertToStringOfLocalTime("23:59:59.999999", "23:59:59.999999");
    assertToStringOfLocalTime("23:59:59.999999", "23:59:59.999999499");
    assertToStringOfLocalTime("24:00:00", "23:59:59.999999500");
    assertToStringOfLocalTime("24:00:00", "23:59:59.999999999");
  }

  private void assertToStringOfLocalTime(String expectedOutput, String inputTime) {
    assertEquals("timestampUtils.toString(LocalTime.parse(" + inputTime + "))",
        expectedOutput,
        timestampUtils.toString(LocalTime.parse(inputTime)));
  }

  @Test
  public void testToLocalTime() throws SQLException {
    assertToLocalTime("00:00:00", "00:00:00");

    assertToLocalTime("00:00:00.1", "00:00:00.1");
    assertToLocalTime("00:00:00.12", "00:00:00.12");
    assertToLocalTime("00:00:00.123", "00:00:00.123");
    assertToLocalTime("00:00:00.1234", "00:00:00.1234");
    assertToLocalTime("00:00:00.12345", "00:00:00.12345");
    assertToLocalTime("00:00:00.123456", "00:00:00.123456");
    assertToLocalTime("00:00:00.999999", "00:00:00.999999");

    assertToLocalTime("23:59:59", "23:59:59");
    assertToLocalTime("23:59:59.999999", "23:59:59.999999");
    assertToLocalTime("23:59:59.9999999", "23:59:59.9999999");
    assertToLocalTime("23:59:59.99999999", "23:59:59.99999999");
    assertToLocalTime("23:59:59.999999998", "23:59:59.999999998");
    assertToLocalTime(LocalTime.MAX.toString(), "24:00:00");
  }

  private void assertToLocalTime(String expectedOutput, String inputTime) throws SQLException {
    assertEquals("timestampUtils.toLocalTime(" + inputTime + ")",
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
    assertToStringOfOffsetTime("23:59:59.999999+01", "23:59:59.999999499+01:00");
    assertToStringOfOffsetTime("24:00:00+01", "23:59:59.999999500+01:00");
    assertToStringOfOffsetTime("24:00:00+01", "23:59:59.999999999+01:00");
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

    assertToOffsetTime("23:59:59.999999+01:00", "23:59:59.999999+01");
    assertToOffsetTime("23:59:59.9999999+01:00", "23:59:59.9999999+01");
    assertToOffsetTime("23:59:59.99999999+01:00", "23:59:59.99999999+01");
    assertToOffsetTime("23:59:59.999999998+01:00", "23:59:59.999999998+01");
    assertToOffsetTime(OffsetTime.MAX.toString(), "24:00:00+01");
  }

  private void assertToOffsetTime(String expectedOutput, String inputTime) throws SQLException {
    assertEquals("timestampUtils.toOffsetTime(" + inputTime + ")",
        OffsetTime.parse(expectedOutput),
        timestampUtils.toOffsetTime(inputTime));
  }
}
