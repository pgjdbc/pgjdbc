/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * A range {@code [off, off + len)} is accepted when it lies within the array, and refused otherwise.
 *
 * <p>The sum {@code off + len} overflows for a large enough pair and wraps to a negative value, so a
 * check written as {@code off + len > array.length} would accept such a range. The refused set
 * includes three of those pairs.</p>
 *
 * <p>The first column of each case is the length of the array the range is checked against.</p>
 */
class ArrayRangesTest {
  @ParameterizedTest
  @CsvSource({
      "0, 0, 0",
      "10, 0, 0",
      "10, 0, 10",
      "10, 10, 0",
      "10, 9, 1",
      "10, 5, 5",
  })
  void rangeWithinTheArrayIsAccepted(int length, int off, int len) {
    ArrayRanges.checkFromIndexSize(new byte[length], off, len);
  }

  @ParameterizedTest
  @CsvSource({
      "0, 0, 1",
      "0, 1, 0",
      "10, -1, 0",
      "10, -1, 4",
      "10, 0, -1",
      "10, -1, -1",
      "10, 11, 0",
      "10, 10, 1",
      "10, 0, 11",
      "10, 5, 6",
      "10, 2147483647, 1",
      "10, 1, 2147483647",
      "10, -2147483648, -2147483648",
  })
  void rangeOutsideTheArrayIsRefused(int length, int off, int len) {
    byte[] array = new byte[length];
    assertThrows(ArrayIndexOutOfBoundsException.class,
        () -> ArrayRanges.checkFromIndexSize(array, off, len),
        () -> "checkFromIndexSize(new byte[" + length + "], " + off + ", " + len + ")");
  }
}
