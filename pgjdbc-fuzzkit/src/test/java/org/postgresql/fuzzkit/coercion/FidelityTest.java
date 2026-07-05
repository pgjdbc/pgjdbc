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
  void deepEquals_matchesEmptyArraysOfAnyRank() {
    // PostgreSQL normalises every empty array to the canonical zero-dimension form, which the codec
    // reads back as a one-dimensional empty array whatever the written rank was. So an empty array
    // round-trips to a rank-1 empty array by contract, and DEEP_EQUALS treats two empty arrays as
    // equal regardless of declared rank rather than flagging that documented collapse as a mismatch.
    assertTrue(Fidelity.DEEP_EQUALS.equal(new int[0], new int[0][0]));
    assertTrue(Fidelity.DEEP_EQUALS.equal(new Integer[0], new Integer[0][0]));
    assertTrue(Fidelity.DEEP_EQUALS.equal(new Integer[0][0][0], new Integer[0]));
    // A non-empty outer dimension whose sub-arrays are all empty is still the empty array (int[2][0]).
    assertTrue(Fidelity.DEEP_EQUALS.equal(new int[2][0], new int[0]));
    assertTrue(Fidelity.DEEP_EQUALS.equal(new Integer[1][2][0], new Integer[0]));
  }

  @Test
  void deepEquals_rejectsDifferentRankWhenNonEmpty() {
    // The class check still catches a genuine rank disagreement between two non-empty arrays.
    assertFalse(Fidelity.DEEP_EQUALS.equal(new int[]{1}, new int[][]{{1}}));
    assertFalse(Fidelity.DEEP_EQUALS.equal(new Integer[]{1}, new Integer[][]{{1}}));
  }

  @Test
  void deepEquals_rejectsUnequalContents() {
    assertFalse(Fidelity.DEEP_EQUALS.equal(
        new int[][]{{1, 2}, {3, 4}}, new int[][]{{1, 2}, {3, 5}}));
  }
}
