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
  private int size;

  public void add(int i) {
    int size = this.size;
    ensureCapacity(size);
    ints[size] = i;
    this.size = size + 1;
  }

  private void ensureCapacity(int size) {
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
    return size;
  }

  public int get(int i) {
    if (i < 0 || i >= size) {
      throw new ArrayIndexOutOfBoundsException("Index: " + i + ", Size: " + size);
    }
    return ints[i];
  }

  public void clear() {
    size = 0;
  }

  /**
   * Returns an array containing all the elements in this list. The modifications of the returned
   * array will not affect this list.
   *
   * @return an array containing all the elements in this list
   */
  public int[] toArray() {
    if (size == 0) {
      return EMPTY_INT_ARRAY;
    }
    return Arrays.copyOf(ints, size);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < size; i++) {
      if (i > 0) {
        sb.append(", ");
      }
      sb.append(ints[i]);
    }
    sb.append("]");
    return sb.toString();
  }
}
