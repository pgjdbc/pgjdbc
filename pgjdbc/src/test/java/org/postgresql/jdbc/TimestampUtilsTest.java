package org.postgresql.jdbc;

import org.junit.Test;

import java.time.LocalTime;
import java.util.TimeZone;

import static org.junit.Assert.*;

public class TimestampUtilsTest {

  @Test
  public void testToStringOfLocalTime() {
    TimestampUtils timestampUtils = new TimestampUtils(true, TimeZone::getDefault);

    assertEquals("00:00:00", timestampUtils.toString(LocalTime.parse("00:00:00")));
    assertEquals("00:00:00.1", timestampUtils.toString(LocalTime.parse("00:00:00.1")));
    assertEquals("00:00:00.12", timestampUtils.toString(LocalTime.parse("00:00:00.12")));
    assertEquals("00:00:00.123", timestampUtils.toString(LocalTime.parse("00:00:00.123")));
    assertEquals("00:00:00.1234", timestampUtils.toString(LocalTime.parse("00:00:00.1234")));
    assertEquals("00:00:00.12345", timestampUtils.toString(LocalTime.parse("00:00:00.12345")));
    assertEquals("00:00:00.123456", timestampUtils.toString(LocalTime.parse("00:00:00.123456")));

    assertEquals("00:00:00.999999", timestampUtils.toString(LocalTime.parse("00:00:00.999999")));
    assertEquals("00:00:00.999999", timestampUtils.toString(LocalTime.parse("00:00:00.999999499")));
    assertEquals("00:00:01", timestampUtils.toString(LocalTime.parse("00:00:00.999999500")));

    assertEquals("23:59:59", timestampUtils.toString(LocalTime.parse("23:59:59")));
    assertEquals("23:59:59.999999", timestampUtils.toString(LocalTime.parse("23:59:59.999999")));
    assertEquals("23:59:59.999999", timestampUtils.toString(LocalTime.parse("23:59:59.999999499")));
    assertEquals("24:00:00", timestampUtils.toString(LocalTime.parse("23:59:59.999999500")));
  }

}
