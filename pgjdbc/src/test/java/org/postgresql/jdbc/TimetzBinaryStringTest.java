/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.time.OffsetTime;
import java.util.TimeZone;

/**
 * Regression for the binary {@code timetz} {@code getString} offset bug.
 *
 * <p>A {@code timetz} carries its own UTC offset in the wire payload, so its string form must reflect
 * that offset and stay the same whatever the session time zone is. The bug was in
 * {@link TimestampUtils#toStringOffsetTimeBin(byte[])}: it decoded the {@link OffsetTime} correctly but
 * then shifted it into the client zone via {@code withClientOffsetSameInstant}, so a value with offset
 * {@code +03:30} came back as {@code +00} on a UTC session and drifted with any other session zone.
 * The text path never did this, so binary and text {@code getString} disagreed.</p>
 */
class TimetzBinaryStringTest {

  /** Renders the binary form of {@code value} as {@code getString} would, under {@code clientZone}. */
  private static String binaryString(String clientZone, OffsetTime value) throws PSQLException {
    TimestampUtils utils = new TimestampUtils(false, () -> TimeZone.getTimeZone(clientZone));
    byte[] binary = TimestampUtils.toBinTimeTz(false, value);
    return utils.toStringOffsetTimeBin(binary);
  }

  @Test
  void preservesWireOffset() throws Exception {
    assertEquals("16:21:50.123456+03:30",
        binaryString("UTC", OffsetTime.parse("16:21:50.123456+03:30")));
  }

  @Test
  void preservesNegativeOffset() throws Exception {
    // The exact input the fuzzer surfaced when the bug was reintroduced.
    assertEquals("13:47:53.595943-11:44",
        binaryString("UTC", OffsetTime.parse("13:47:53.595943-11:44")));
  }

  @Test
  void independentOfSessionZone() throws Exception {
    // Same value, three different session zones: the offset comes from the wire, not the session,
    // so the string must not change. Before the fix each zone produced a different offset.
    OffsetTime value = OffsetTime.parse("16:21:50.123456+03:30");
    String expected = "16:21:50.123456+03:30";
    assertEquals(expected, binaryString("UTC", value));
    assertEquals(expected, binaryString("GMT+5", value));
    assertEquals(expected, binaryString("GMT-8", value));
  }
}
