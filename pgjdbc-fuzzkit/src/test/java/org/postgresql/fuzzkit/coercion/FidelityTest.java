/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Unit tests for the {@link Fidelity} comparisons, focused on the hardened {@link Fidelity#DEEP_EQUALS}. */
class FidelityTest {

  @Test
  void deepEquals_matchesEqualNestedArrays() {
    assertTrue(Fidelity.DEEP_EQUALS.equal(
        new int[][]{{1, 2}, {3, 4}}, new int[][]{{1, 2}, {3, 4}}));
    assertTrue(Fidelity.DEEP_EQUALS.equal(
        new Integer[]{1, null, 3}, new Integer[]{1, null, 3}));
    assertTrue(Fidelity.DEEP_EQUALS.equal(new int[]{}, new int[]{}));
  }

  @Test
  void deepEquals_rejectsDifferentRankEvenWhenEmpty() {
    // Arrays.deepEquals alone treats two empty arrays of different rank as equal; the class check
    // makes DEEP_EQUALS reject the shape drift a text-vs-binary decode of an empty array would show.
    assertFalse(Fidelity.DEEP_EQUALS.equal(new int[0], new int[0][0]));
    assertFalse(Fidelity.DEEP_EQUALS.equal(new Integer[0], new Integer[0][0]));
  }

  @Test
  void deepEquals_rejectsUnequalContents() {
    assertFalse(Fidelity.DEEP_EQUALS.equal(
        new int[][]{{1, 2}, {3, 4}}, new int[][]{{1, 2}, {3, 5}}));
  }
}
