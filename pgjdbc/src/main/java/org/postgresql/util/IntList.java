/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.util.Arrays;

/**
 * A specialized class to store a list of {@code int} values, so it does not need auto-boxing. Note:
 * this is a driver-internal class, and it is not intended to be used outside the driver.
 */
public final class IntList {
  private static final int[] EMPTY_INT_ARRAY = new int[0];
  private int[] ints = EMPTY_INT_ARRAY;
  private int index = 0;

  public void add(int i) {
    int index = this.index;
    ensureSize(index);
    ints[index] = i;
    this.index = index + 1;
  }

  private void ensureSize(int size) {
    int length = ints.length;
    if (size >= length) {
      // double in size until 1024 in size, then grow by 1.5x
      final int newLength = length == 0 ? 8 :
          length < 1024 ? length << 1 :
              (length + (length >> 1));
      ints = Arrays.copyOf(ints, newLength);
    }
  }

  public int size() {
    return index;
  }

  public int get(int i) {
    if (i < 0 || i >= index) {
      throw new ArrayIndexOutOfBoundsException("Index: " + i + ", Size: " + index);
    }
    return ints[i];
  }

  public void clear() {
    index = 0;
  }

  /**
   * Returns an array containing all the elements in this list. The modifications of the returned
   * array will not affect this list.
   *
   * @return an array containing all the elements in this list
   */
  public int[] toArray() {
    if (index == 0) {
      return EMPTY_INT_ARRAY;
    }
    return Arrays.copyOf(ints, index);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < index; ++i) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(ints[i]);
    }
    sb.append("]");
    return sb.toString();
  }
}
