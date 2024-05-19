/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;

public class IntSetTest {
  private final IntSet intSet = new IntSet();

  @ParameterizedTest
  @ValueSource(ints = {Oid.UNSPECIFIED, Oid.INT4, Oid.BYTEA, Oid.VARCHAR, Integer.MIN_VALUE,
      Integer.MAX_VALUE})
  void empty_contains_no_values(int oid) {
    assertFalse(intSet.contains(oid), () -> "empty intset should not contain oid " + oid);
  }

  @ParameterizedTest
  @ValueSource(ints = {Oid.UNSPECIFIED, Oid.INT4, Oid.BYTEA, Oid.VARCHAR, Integer.MIN_VALUE,
      Integer.MAX_VALUE})
  void contains_added_value(int oid) {
    intSet.add(oid);
    assertTrue(intSet.contains(oid),
        () -> "intset should contain the oid that was just added: " + oid);
  }

  @ParameterizedTest
  @ValueSource(ints = {Oid.UNSPECIFIED, Oid.INT4, Oid.BYTEA, Oid.VARCHAR, Integer.MIN_VALUE,
      Integer.MAX_VALUE})
  void clear_removes_elements(int oid) {
    intSet.add(oid);
    intSet.clear();
    assertFalse(intSet.contains(oid),
        () -> "intset should contain oid " + oid + " after .clear()");
  }

  @ParameterizedTest
  @ValueSource(ints = {Oid.UNSPECIFIED, Oid.INT4, Oid.BYTEA, Oid.VARCHAR, Integer.MIN_VALUE,
      Integer.MAX_VALUE})
  void does_not_contain_other_values(int oid) {
    intSet.add(Oid.INT8);
    intSet.add(Oid.BIT);
    assertFalse(intSet.contains(oid),
        () -> "intset contains only INT8 and BIT, so it should not contain oid " + oid);
  }

  @Test
  void addAll_contains_all_values() {
    List<Integer> values = Arrays.asList(Oid.UNSPECIFIED, Oid.INT4, Oid.BYTEA, Oid.VARCHAR);
    intSet.addAll(values);
    for (Integer value : values) {
      assertTrue(intSet.contains(value),
          () -> "intset should contain oid " + value + " after addAdd(" + values + ")");
    }
  }

  @Test
  void addAll_does_not_contains_other_values() {
    List<Integer> values = Arrays.asList(Oid.UNSPECIFIED, Oid.INT4, Oid.BYTEA, Oid.VARCHAR);
    intSet.addAll(values);
    assertFalse(intSet.contains(Oid.BIT),
        () -> "intset should not contain BIT after addAll(" + values + ")");
  }
}
