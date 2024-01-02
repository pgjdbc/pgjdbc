/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link IntList}.
 */
class IntListTest {

  @Test
  void size() {
    final IntList list = new IntList();
    assertEquals(0, list.size());
    list.add(3);
    assertEquals(1, list.size());

    for (int i = 0; i < 48; i++) {
      list.add(i);
    }
    assertEquals(49, list.size());

    list.clear();
    assertEquals(0, list.size());
  }

  @Test
  void get_empty() {
    final IntList list = new IntList();
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> list.get(0));
  }

  @Test
  void get_negative() {
    final IntList list = new IntList();
    list.add(3);
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> list.get(-1));
  }

  @Test
  void get_tooLarge() {
    final IntList list = new IntList();
    list.add(3);
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> list.get(1));
  }

  @Test
  void get() {
    final IntList list = new IntList();
    list.add(3);
    assertEquals(3, list.get(0));

    for (int i = 0; i < 1048; i++) {
      list.add(i);
    }

    assertEquals(3, list.get(0));

    for (int i = 0; i < 1048; i++) {
      assertEquals(i, list.get(i + 1));
    }

    list.clear();
    list.add(4);
    assertEquals(4, list.get(0));
  }

  @Test
  void toArray() {
    int[] emptyArray = new IntList().toArray();
    IntList list = new IntList();
    assertSame(emptyArray, list.toArray(), "emptyList.toArray()");

    list.add(45);
    assertArrayEquals(new int[]{45}, list.toArray());

    list.clear();
    assertSame(emptyArray, list.toArray(), "emptyList.toArray() after clearing the list");

    final int[] expected = new int[1048];
    for (int i = 0; i < 1048; i++) {
      list.add(i);
      expected[i] = i;
    }
    assertArrayEquals(expected, list.toArray());
  }
}
