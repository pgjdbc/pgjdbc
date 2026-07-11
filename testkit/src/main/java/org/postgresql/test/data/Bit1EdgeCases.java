/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code bit(1)} values. A single-bit {@code bit(1)} is special in pgjdbc -- it maps to a
 * {@code Boolean} and answers {@code getBoolean} -- so it decodes differently from a wider bit string, and
 * the two bit values are worth checking on their own.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class Bit1EdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private Bit1EdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(new EdgeCase("zero", "0", null));
    out.add(new EdgeCase("one", "1", null));
    return out;
  }
}
