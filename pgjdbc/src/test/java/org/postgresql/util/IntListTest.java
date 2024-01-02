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
public class IntListTest {

  @Test
  public void testSize() {
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
  public void testGet_empty() {
    final IntList list = new IntList();
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> list.get(0));
  }

  @Test
  public void testGet_negative() {
    final IntList list = new IntList();
    list.add(3);
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> list.get(-1));
  }

  @Test
  public void testGet_tooLarge() {
    final IntList list = new IntList();
    list.add(3);
    assertThrows(ArrayIndexOutOfBoundsException.class, () -> list.get(1));
  }

  @Test
  public void testGet() {
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
  public void testToArray() {
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
