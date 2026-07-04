/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Objects;

/**
 * How a written value is compared with the value read back on an identity round-trip. This is the
 * canonical set a {@link ScalarDescriptor} carries, mirroring the comparisons the round-trip fuzzer
 * support inlines today:
 *
 * <ul>
 *   <li>{@link #EQUALS} -- exact equality, for a type whose value survives byte-for-byte.</li>
 *   <li>{@link #NUMERIC_EQUAL} -- numeric equality ignoring scale, because a {@code numeric} decode may
 *       drop trailing zeros ({@code 1.50} reads back as {@code 1.5}).</li>
 *   <li>{@link #SAME_INSTANT} -- the same moment regardless of the read-back offset, because
 *       {@code timestamptz} is an instant and the driver reads it back in the session zone rather than
 *       the written one.</li>
 *   <li>{@link #BYTES_EQUAL} -- element-wise {@code byte[]} equality, because {@code bytea} decodes to a
 *       fresh array whose reference-based {@code equals} would never match, mirroring the
 *       {@code assertArrayEquals} the bytea codec round-trip uses.</li>
 *   <li>{@link #DEEP_EQUALS} -- recursive array equality ({@link Arrays#deepEquals}), because a
 *       (possibly multi-dimensional) array decodes to a fresh nested array whose reference-based
 *       {@code equals} would never match. This is the {@link ArrayDescriptor} fidelity: it covers every
 *       {@code ndim} and both leaf representations ({@code Integer[][]} and {@code int[][]}) with a
 *       single comparison, since {@code deepEquals} unwraps primitive leaves too.</li>
 * </ul>
 */
public enum Fidelity {
  /** The value read back must equal the value written. */
  EQUALS {
    @Override
    public boolean equal(Object written, Object read) {
      return Objects.equals(written, read);
    }
  },
  /** The two {@link BigDecimal} values must be numerically equal, ignoring scale. */
  NUMERIC_EQUAL {
    @Override
    public boolean equal(Object written, Object read) {
      return ((BigDecimal) written).compareTo((BigDecimal) read) == 0;
    }
  },
  /** The two {@link OffsetDateTime} values must denote the same instant, whatever their offsets. */
  SAME_INSTANT {
    @Override
    public boolean equal(Object written, Object read) {
      return ((OffsetDateTime) written).isEqual((OffsetDateTime) read);
    }
  },
  /** The two {@code byte[]} values must be element-wise equal. */
  BYTES_EQUAL {
    @Override
    public boolean equal(Object written, Object read) {
      return Arrays.equals((byte[]) written, (byte[]) read);
    }
  },
  /** The two arrays must be recursively equal, unwrapping every dimension and primitive leaf. */
  DEEP_EQUALS {
    @Override
    public boolean equal(Object written, Object read) {
      if (written == null || read == null) {
        return written == read;
      }
      // The runtime array class must match as well as the contents. Arrays.deepEquals treats two
      // empty nested arrays of different rank as equal (an int[0] against an int[0][0]), so it would
      // hide a text-vs-binary disagreement on the dimensionality of an empty array. Requiring the
      // same class first catches that shape drift; deepEquals then covers every dimension and both
      // leaf representations of the contents.
      if (written.getClass() != read.getClass()) {
        return false;
      }
      return Arrays.deepEquals(new Object[]{written}, new Object[]{read});
    }
  };

  /** Whether the value read back matches the value written under this fidelity. */
  public abstract boolean equal(Object written, Object read);
}
