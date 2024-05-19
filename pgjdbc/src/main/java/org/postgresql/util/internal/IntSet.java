/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.core.Oid;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Read-optimized {@code Set} for storing {@link Oid} values.
 */
public class IntSet {
  /**
   * Maximal Oid that will bs stored in {@link BitSet}.
   * If Oid exceeds this value, then it will be stored in {@code Set<Int>} only.
   * In theory, Oids can be up to 32bit, so we want to limit per-connection memory utilization.
   * Allow {@code BitSet} to consume up to 8KiB (one for send and one for receive).
   */
  private static final int MAX_OID_TO_STORE_IN_BITSET = 8192 * 8;

  /**
   * Contains all the values.
   * Note: we could skip storing small values in ths set, however,
   * it would slow down {@link #asSet()}.
   */
  private final Set<Integer> set = new HashSet<>();

  /**
   * Contains values in range of [0..MAX_OID_TO_STORE_IN_BITSET].
   */
  private final BitSet bitSet = new BitSet();

  /**
   * Is true in case {@link #bitSet} misses some of the values stored in {@link #set}.
   */
  private boolean lossy;

  /**
   * Clears the contents of the set.
   */
  public void clear() {
    set.clear();
    bitSet.clear();
    lossy = false;
  }

  /**
   * Adds all the values to the set.
   * @param values set of values to add
   */
  public void addAll(Collection<? extends Integer> values) {
    set.addAll(values);
    for (Integer value : values) {
      if (value <= MAX_OID_TO_STORE_IN_BITSET) {
        bitSet.set(value);
      } else {
        lossy = true;
      }
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
      bitSet.set(value);
    } else {
      lossy = true;
    }
    return set.add(value);
  }

  /**
   * Removes a value from the set.
   * @param value value to remove
   * @return true if the element was
   */
  public boolean remove(int value) {
    bitSet.clear(value);
    return set.remove(value);
  }

  /**
   * Checks if a given value belongs to the set.
   * @param value value to check
   * @return true if the value belons to the set
   */
  public boolean contains(int value) {
    if (value >= 0 && value <= MAX_OID_TO_STORE_IN_BITSET) {
      return bitSet.get(value);
    } else if (!lossy) {
      // We know bitSet contains all the values, so values exceeding its limits are absent
      return false;
    }
    return set.contains(value);
  }

  /**
   * Returns {@link Set} view of the current set.
   * @return Set view
   */
  public Set<? extends Integer> asSet() {
    return set;
  }
}
