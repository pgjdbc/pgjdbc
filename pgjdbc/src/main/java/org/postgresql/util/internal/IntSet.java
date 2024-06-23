/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.core.Oid;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Read-optimized {@code Set} for storing {@link Oid} values.
 */
public final class IntSet {
  /**
   * Maximal Oid that will bs stored in {@link BitSet}.
   * If Oid exceeds this value, then it will be stored in {@code Set<Int>} only.
   * In theory, Oids can be up to 32bit, so we want to limit per-connection memory utilization.
   * Allow {@code BitSet} to consume up to 8KiB (one for send and one for receive).
   */
  private static final int MAX_OID_TO_STORE_IN_BITSET = 8192 * 8;

  /**
   * Contains values outside [0..MAX_OID_TO_STORE_IN_BITSET] range.
   * This field is null if bitSet contains all the values.
   */
  private @Nullable Set<Integer> set;

  /**
   * Contains values in range of [0..MAX_OID_TO_STORE_IN_BITSET].
   */
  private final BitSet bitSet = new BitSet();

  /**
   * Clears the contents of the set.
   */
  public void clear() {
    set = null;
    bitSet.clear();
  }

  /**
   * Adds all the values to the set.
   * @param values set of values to add
   */
  public void addAll(Collection<? extends Integer> values) {
    for (Integer value : values) {
      add(value);
    }
  }

  /**
   * Adds a single value to the set.
   *
   * @param value value to add
   * @return true if the set did not already contain the specified value
   */
  public boolean add(int value) {
    if (value >= 0 && value <= MAX_OID_TO_STORE_IN_BITSET) {
      boolean contains = bitSet.get(value);
      if (!contains) {
        bitSet.set(value);
        return true;
      }
      return false;
    }
    Set<Integer> set = this.set;
    if (set == null) {
      this.set = set = new HashSet<>();
    }
    return set.add(value);
  }

  /**
   * Removes a value from the set.
   * @param value value to remove
   * @return true if the element was
   */
  public boolean remove(int value) {
    if (value >= 0 && value <= MAX_OID_TO_STORE_IN_BITSET) {
      boolean contains = bitSet.get(value);
      if (contains) {
        bitSet.clear(value);
        return true;
      }
      return false;
    }
    Set<Integer> set = this.set;
    return set != null && set.remove(value);
  }

  /**
   * Checks if a given value belongs to the set.
   * @param value value to check
   * @return true if the value belons to the set
   */
  public boolean contains(int value) {
    if (value >= 0 && value <= MAX_OID_TO_STORE_IN_BITSET) {
      return bitSet.get(value);
    }
    Set<Integer> set = this.set;
    return set != null && set.contains(value);
  }

  /**
   * Returns a mutable snapshot of the values stored in the current set.
   * @return a mutable snapshot of the values stored in the current set
   */
  public Set<Integer> toMutableSet() {
    Set<Integer> set = this.set;
    Set<Integer> result = new HashSet<>(
        (int) ((bitSet.cardinality() + (set != null ? set.size() : 0)) / 0.75f));
    if (set != null) {
      result.addAll(set);
    }
    bitSet.stream().forEach(result::add);
    return result;
  }
}
