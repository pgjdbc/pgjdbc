/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A limited message is never longer than the limit, and a shortened one ends with
 * {@link LogMessageUtils#TRUNCATION_SUFFIX} or, for a string literal, with {@code ...}.
 */
class LogMessageUtilsTest {
  /** A character outside the Basic Multilingual Plane, written as a surrogate pair. */
  private static final String EMOJI = new String(Character.toChars(0x1F600));

  @Test
  void truncateDoesNotChangeShortMessages() {
    assertEquals("hello", LogMessageUtils.truncate("hello", 100));
    assertEquals("hello", LogMessageUtils.truncate("hello", 5));
  }

  @Test
  void truncateKeepsTheWholeMessageWithoutALimit() {
    String huge = repeat('x', 100_000);
    assertEquals(huge, LogMessageUtils.truncate(huge, 0));
  }

  @Test
  void truncateAppendsTheSuffix() {
    assertEquals("abcdef...(truncated)", LogMessageUtils.truncate("abcdefghijklmnopqrstuvwxyz", 20));
  }

  @ParameterizedTest
  @CsvSource({
      "1, .",
      "5, ...(t",
      "14, ...(truncated)",
  })
  void aLimitNoLongerThanTheSuffixKeepsOnlyAPrefixOfTheSuffix(int limit, String expected) {
    assertEquals(expected, LogMessageUtils.truncate("abcdefghijklmnopqrstuvwxyz0123456789", limit));
  }

  @Test
  void truncateRejectsNull() {
    assertThrows(IllegalArgumentException.class, () -> LogMessageUtils.truncate(null, 10));
  }

  @Test
  void appendBoundedAppendsAValueThatFits() {
    StringBuilder sb = new StringBuilder("pre:");
    assertTrue(LogMessageUtils.appendBounded(sb, "value", 100));
    assertEquals("pre:value", sb.toString());
  }

  @Test
  void appendBoundedAppendsAValueThatExactlyFillsTheRoomLeft() {
    StringBuilder sb = new StringBuilder("pre:");
    assertTrue(LogMessageUtils.appendBounded(sb, "value", 9));
    assertEquals("pre:value", sb.toString());
  }

  @Test
  void appendBoundedTruncates() {
    StringBuilder sb = new StringBuilder("pre:");
    assertFalse(LogMessageUtils.appendBounded(sb, "abcdefghijklmnopqrstuvwxyz", 20));
    assertEquals("pre:ab...(truncated)", sb.toString());
  }

  @Test
  void appendBoundedWithRoomShorterThanTheSuffixAppendsAPrefixOfTheSuffix() {
    StringBuilder sb = new StringBuilder("pre:");
    assertFalse(LogMessageUtils.appendBounded(sb, "abcdefghijklmnopqrstuvwxyz", 10));
    assertEquals("pre:...(tr", sb.toString());
  }

  @Test
  void appendBoundedWithNoRoomLeavesTheBufferUnchanged() {
    StringBuilder sb = new StringBuilder("pre:");
    assertFalse(LogMessageUtils.appendBounded(sb, "abcdefghijklmnopqrstuvwxyz", 4));
    assertEquals("pre:", sb.toString());
  }

  @Test
  void appendBoundedWithoutALimitAppendsEverything() {
    StringBuilder sb = new StringBuilder();
    String value = repeat('x', 50_000);
    assertTrue(LogMessageUtils.appendBounded(sb, value, 0));
    assertEquals(value, sb.toString());
  }

  @ParameterizedTest
  @CsvSource(value = {
      "6| false| ",
      "7| true| ('''...",
      "8| true| ('''...",
      "9| true| ('''''...",
  }, delimiter = '|')
  void aQuotedPrefixCutsOnlyBetweenDoubledQuotes(int room, boolean appended, String expected) {
    StringBuilder sb = new StringBuilder();
    assertEquals(appended, LogMessageUtils.appendQuotedStringPrefix(sb, "''''", true, room));
    assertEquals(expected == null ? "" : expected, sb.toString());
  }

  @Test
  void aQuotedPrefixDoublesBackslashesOnlyWithoutStandardConformingStrings() {
    StringBuilder off = new StringBuilder("x");
    assertTrue(LogMessageUtils.appendQuotedStringPrefix(off, "\\\\\\", false, 9));
    assertEquals("x('\\\\\\\\...", off.toString());
    StringBuilder on = new StringBuilder("x");
    assertTrue(LogMessageUtils.appendQuotedStringPrefix(on, "\\\\\\", true, 9));
    assertEquals("x('\\\\\\...", on.toString());
  }

  @Test
  void aQuotedPrefixKeepsSurrogatePairsWhole() {
    StringBuilder sb = new StringBuilder();
    assertTrue(LogMessageUtils.appendQuotedStringPrefix(sb, "a" + EMOJI + "b", true, 7));
    assertEquals("('a...", sb.toString());
    sb.setLength(0);
    assertTrue(LogMessageUtils.appendQuotedStringPrefix(sb, "a" + EMOJI + "b", true, 8));
    assertEquals("('a" + EMOJI + "...", sb.toString());
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    java.util.Arrays.fill(chars, c);
    return new String(chars);
  }
}
