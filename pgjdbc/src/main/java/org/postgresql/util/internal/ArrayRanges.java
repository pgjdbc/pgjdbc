/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.util.GT;

/**
 * Bounds checks for methods that take an offset and a length into a byte array.
 *
 * <p>This is an internal class, and it is not meant to be used as a public API. It stands in for
 * {@code java.util.Objects.checkFromIndexSize}, which the driver cannot call because it compiles
 * against Java 8, and which throws {@code IndexOutOfBoundsException} where this throws the array
 * subclass.</p>
 */
public class ArrayRanges {
  private ArrayRanges() {
  }

  /**
   * Checks that {@code [off, off + len)} is a range within {@code array}.
   *
   * @param array array the range must fit in
   * @param off index of the first byte in the range
   * @param len number of bytes in the range
   * @throws ArrayIndexOutOfBoundsException if {@code off} or {@code len} is negative, or if
   *         {@code off + len} exceeds {@code array.length}
   */
  public static void checkFromIndexSize(byte[] array, int off, int len) {
    // (off | len) < 0 is true when either is negative, so the second clause runs only for
    // non-negative values, where array.length - off cannot overflow
    if ((off | len) < 0 || len > array.length - off) {
      throw new ArrayIndexOutOfBoundsException(
          GT.tr("Range [{0}, {0} + {1}) out of bounds for length {2}",
              String.valueOf(off), String.valueOf(len), String.valueOf(array.length)));
    }
  }
}
