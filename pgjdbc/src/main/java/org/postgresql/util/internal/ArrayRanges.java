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
 * against Java 8.</p>
 */
public final class ArrayRanges {
  private ArrayRanges() {
  }

  /**
   * Checks that {@code [off, off + len)} is a range within {@code array}.
   *
   * @param array array the range must fit in
   * @param off index of the first byte in the range
   * @param len number of bytes in the range
   * @throws NullPointerException if {@code array} is {@code null}, whatever {@code off} and
   *         {@code len} are
   * @throws IndexOutOfBoundsException if {@code off} or {@code len} is negative, or if
   *         {@code off + len} exceeds {@code array.length}
   */
  public static void checkFromIndexSize(byte[] array, int off, int len) {
    // Read before the range is checked, so a null array fails the same way for every off and len
    int length = array.length;
    // (off | len) < 0 is true when either is negative, so the second clause runs only for
    // non-negative values, where length - off cannot overflow
    if ((off | len) < 0 || len > length - off) {
      throw new IndexOutOfBoundsException(
          GT.tr("Range [{0}, {0} + {1}) out of bounds for length {2}",
              String.valueOf(off), String.valueOf(len), String.valueOf(length)));
    }
  }
}
