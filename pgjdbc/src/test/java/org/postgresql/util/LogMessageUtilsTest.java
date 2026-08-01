/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogMessageUtilsTest {

  @Test
  void truncateDoesNotChangeShortMessages() {
    assertEquals("hello", LogMessageUtils.truncate("hello", 100));
    assertEquals("hello", LogMessageUtils.truncate("hello", 5));
  }

  @Test
  void truncateUnlimitedWhenMaxNonPositive() {
    String huge = repeat('x', 100_000);
    assertEquals(huge, LogMessageUtils.truncate(huge, 0));
    assertEquals(huge, LogMessageUtils.truncate(huge, -1));
  }

  @Test
  void truncateAppendsSuffix() {
    String input = "abcdefghijklmnopqrstuvwxyz";
    String truncated = LogMessageUtils.truncate(input, 20);
    assertEquals(20, truncated.length());
    assertTrue(truncated.endsWith(LogMessageUtils.TRUNCATION_SUFFIX));
    assertTrue(truncated.startsWith("abcdef"));
  }

  @Test
  void truncateVerySmallMaxLength() {
    assertEquals(".", LogMessageUtils.truncate("abcdefgh", 1));
    assertEquals("...", LogMessageUtils.truncate("abcdefgh", 3));
    String longInput = "abcdefghijklmnopqrstuvwxyz0123456789";
    assertEquals(LogMessageUtils.TRUNCATION_SUFFIX,
        LogMessageUtils.truncate(longInput, LogMessageUtils.TRUNCATION_SUFFIX.length()));
  }

  @Test
  void truncateRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> LogMessageUtils.truncate(null, 10));
  }

  @Test
  void appendBoundedFitsFully() {
    StringBuilder sb = new StringBuilder("pre:");
    assertTrue(LogMessageUtils.appendBounded(sb, "value", 100));
    assertEquals("pre:value", sb.toString());
  }

  @Test
  void appendBoundedTruncates() {
    StringBuilder sb = new StringBuilder("pre:");
    assertFalse(LogMessageUtils.appendBounded(sb, "abcdefghijklmnopqrstuvwxyz", 20));
    assertEquals(20, sb.length());
    assertTrue(sb.toString().endsWith(LogMessageUtils.TRUNCATION_SUFFIX));
    assertTrue(sb.toString().startsWith("pre:"));
  }

  @Test
  void appendBoundedUnlimited() {
    StringBuilder sb = new StringBuilder();
    String value = repeat('x', 50_000);
    assertTrue(LogMessageUtils.appendBounded(sb, value, 0));
    assertEquals(value, sb.toString());
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    java.util.Arrays.fill(chars, c);
    return new String(chars);
  }
}
