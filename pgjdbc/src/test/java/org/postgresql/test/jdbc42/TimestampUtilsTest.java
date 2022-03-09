/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;

import org.postgresql.core.Provider;
import org.postgresql.jdbc.TimestampUtils;

import org.junit.Test;

import java.sql.SQLException;
import java.time.LocalTime;
import java.time.OffsetTime;
import java.util.TimeZone;

public class TimestampUtilsTest {
  @Test
  public void testToStringOfLocalTime() {
    TimestampUtils timestampUtils = createTimestampUtils();

    assertEquals("00:00:00", timestampUtils.toString(LocalTime.parse("00:00:00")));
    assertEquals("00:00:00.1", timestampUtils.toString(LocalTime.parse("00:00:00.1")));
    assertEquals("00:00:00.12", timestampUtils.toString(LocalTime.parse("00:00:00.12")));
    assertEquals("00:00:00.123", timestampUtils.toString(LocalTime.parse("00:00:00.123")));
    assertEquals("00:00:00.1234", timestampUtils.toString(LocalTime.parse("00:00:00.1234")));
    assertEquals("00:00:00.12345", timestampUtils.toString(LocalTime.parse("00:00:00.12345")));
    assertEquals("00:00:00.123456", timestampUtils.toString(LocalTime.parse("00:00:00.123456")));

    assertEquals("00:00:00.999999", timestampUtils.toString(LocalTime.parse("00:00:00.999999")));
    assertEquals("499 nanosecs difference should round down", "00:00:00.999999", timestampUtils.toString(LocalTime.parse("00:00:00.999999499")));
    assertEquals("500 nanosecs difference should round up", "00:00:01", timestampUtils.toString(LocalTime.parse("00:00:00.999999500")));

    assertEquals("23:59:59", timestampUtils.toString(LocalTime.parse("23:59:59")));

    assertEquals("0 nanosecs difference should stay identical", "23:59:59.999999", timestampUtils.toString(LocalTime.parse("23:59:59.999999")));
    assertEquals("499 nanosecs difference should round down", "23:59:59.999999", timestampUtils.toString(LocalTime.parse("23:59:59.999999499")));
    assertEquals("500 nanosecs difference should map to special time 24:00:00", "24:00:00", timestampUtils.toString(LocalTime.parse("23:59:59.999999500")));
    assertEquals("999 nanosecs difference should map to special time 24:00:00", "24:00:00", timestampUtils.toString(LocalTime.parse("23:59:59.999999999")));
  }

  @Test
  public void testToLocalTime() throws SQLException {
    TimestampUtils timestampUtils = createTimestampUtils();

    assertEquals(LocalTime.parse("00:00:00"), timestampUtils.toLocalTime("00:00:00"));

    assertEquals(LocalTime.parse("00:00:00.1"), timestampUtils.toLocalTime("00:00:00.1"));
    assertEquals(LocalTime.parse("00:00:00.12"), timestampUtils.toLocalTime("00:00:00.12"));
    assertEquals(LocalTime.parse("00:00:00.123"), timestampUtils.toLocalTime("00:00:00.123"));
    assertEquals(LocalTime.parse("00:00:00.1234"), timestampUtils.toLocalTime("00:00:00.1234"));
    assertEquals(LocalTime.parse("00:00:00.12345"), timestampUtils.toLocalTime("00:00:00.12345"));
    assertEquals(LocalTime.parse("00:00:00.123456"), timestampUtils.toLocalTime("00:00:00.123456"));
    assertEquals(LocalTime.parse("00:00:00.999999"), timestampUtils.toLocalTime("00:00:00.999999"));

    assertEquals(LocalTime.parse("23:59:59"), timestampUtils.toLocalTime("23:59:59"));
    assertEquals("0 nanosecs difference", LocalTime.parse("23:59:59.999999"), timestampUtils.toLocalTime("23:59:59.999999"));
    assertEquals("900 nanosecs difference", LocalTime.parse("23:59:59.9999999"), timestampUtils.toLocalTime("23:59:59.9999999"));
    assertEquals("990 nanosecs difference", LocalTime.parse("23:59:59.99999999"), timestampUtils.toLocalTime("23:59:59.99999999"));
    assertEquals("998 nanosecs difference", LocalTime.parse("23:59:59.999999998"), timestampUtils.toLocalTime("23:59:59.999999998"));
    assertEquals("special time 24:00:00 should be mapped to OffsetTime.MAX", LocalTime.MAX, timestampUtils.toLocalTime("24:00:00"));
  }

  @Test
  public void testToStringOfOffsetTime() {
    TimestampUtils timestampUtils = createTimestampUtils();

    assertEquals("00:00:00+00", timestampUtils.toString(OffsetTime.parse("00:00:00+00:00")));
    assertEquals("00:00:00.1+01", timestampUtils.toString(OffsetTime.parse("00:00:00.1+01:00")));
    assertEquals("00:00:00.12+12", timestampUtils.toString(OffsetTime.parse("00:00:00.12+12:00")));
    assertEquals("00:00:00.123-01", timestampUtils.toString(OffsetTime.parse("00:00:00.123-01:00")));
    assertEquals("00:00:00.1234-02", timestampUtils.toString(OffsetTime.parse("00:00:00.1234-02:00")));
    assertEquals("00:00:00.12345-12", timestampUtils.toString(OffsetTime.parse("00:00:00.12345-12:00")));
    assertEquals("00:00:00.123456+01:30", timestampUtils.toString(OffsetTime.parse("00:00:00.123456+01:30")));
    assertEquals("00:00:00.123456-12:34", timestampUtils.toString(OffsetTime.parse("00:00:00.123456-12:34")));

    assertEquals("23:59:59+01", timestampUtils.toString(OffsetTime.parse("23:59:59+01:00")));

    assertEquals("0 nanosecs difference should stay identical", "23:59:59.999999+01", timestampUtils.toString(OffsetTime.parse("23:59:59.999999+01:00")));
    assertEquals("499 nanosecs difference should round down", "23:59:59.999999+01", timestampUtils.toString(OffsetTime.parse("23:59:59.999999499+01:00")));
    assertEquals("500 nanosecs difference should map to special time 24:00:00", "24:00:00+01", timestampUtils.toString(OffsetTime.parse("23:59:59.999999500+01:00")));
    assertEquals("999 nanosecs difference should map to special time 24:00:00", "24:00:00+01", timestampUtils.toString(OffsetTime.parse("23:59:59.999999999+01:00")));
  }

  @Test
  public void testToOffsetTime() throws SQLException {
    TimestampUtils timestampUtils = createTimestampUtils();

    assertEquals(OffsetTime.parse("00:00:00+00:00"), timestampUtils.toOffsetTime("00:00:00+00"));
    assertEquals(OffsetTime.parse("00:00:00.1+01:00"), timestampUtils.toOffsetTime("00:00:00.1+01"));
    assertEquals(OffsetTime.parse("00:00:00.12+12:00"), timestampUtils.toOffsetTime("00:00:00.12+12"));
    assertEquals(OffsetTime.parse("00:00:00.123-01:00"), timestampUtils.toOffsetTime("00:00:00.123-01"));
    assertEquals(OffsetTime.parse("00:00:00.1234-02:00"), timestampUtils.toOffsetTime("00:00:00.1234-02"));
    assertEquals(OffsetTime.parse("00:00:00.12345-12:00"), timestampUtils.toOffsetTime("00:00:00.12345-12"));
    assertEquals(OffsetTime.parse("00:00:00.123456+01:30"), timestampUtils.toOffsetTime("00:00:00.123456+01:30"));
    assertEquals(OffsetTime.parse("00:00:00.123456-12:34"), timestampUtils.toOffsetTime("00:00:00.123456-12:34"));

    assertEquals("0 nanosecs difference", OffsetTime.parse("23:59:59.999999+01:00"), timestampUtils.toOffsetTime("23:59:59.999999+01"));
    assertEquals("900 nanosecs difference", OffsetTime.parse("23:59:59.9999999+01:00"), timestampUtils.toOffsetTime("23:59:59.9999999+01"));
    assertEquals("990 nanosecs difference", OffsetTime.parse("23:59:59.99999999+01:00"), timestampUtils.toOffsetTime("23:59:59.99999999+01"));
    assertEquals("998 nanosecs difference", OffsetTime.parse("23:59:59.999999998+01:00"), timestampUtils.toOffsetTime("23:59:59.999999998+01"));
    assertEquals("special time 24:00:00 should be mapped to OffsetTime.MAX", OffsetTime.MAX, timestampUtils.toOffsetTime("24:00:00+01"));
  }

  private TimestampUtils createTimestampUtils() {
    return new TimestampUtils(true, (Provider<TimeZone>) TimeZone::getDefault);
  }
}
