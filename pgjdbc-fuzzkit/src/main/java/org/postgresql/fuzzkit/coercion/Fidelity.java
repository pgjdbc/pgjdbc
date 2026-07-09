/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit.coercion;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.reflect.Array;
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
 *       single comparison, since {@code deepEquals} unwraps primitive leaves too. An empty array is a
 *       special case: PostgreSQL normalises every empty array to the canonical zero-dimension form, so
 *       an empty array of any rank reads back as a one-dimensional empty array, and two empty arrays
 *       compare equal regardless of their declared rank.</li>
 * </ul>
 */
public enum Fidelity {
  /** The value read back must equal the value written. */
  EQUALS {
    @Override
    public boolean equal(@Nullable Object written, @Nullable Object read) {
      return Objects.equals(written, read);
    }
  },
  /** The two {@link BigDecimal} values must be numerically equal, ignoring scale. */
  NUMERIC_EQUAL {
    @Override
    public boolean equal(@Nullable Object written, @Nullable Object read) {
      if (written == null || read == null) {
        return written == read;
      }
      return ((BigDecimal) written).compareTo((BigDecimal) read) == 0;
    }
  },
  /** The two {@link OffsetDateTime} values must denote the same instant, whatever their offsets. */
  SAME_INSTANT {
    @Override
    public boolean equal(@Nullable Object written, @Nullable Object read) {
      if (written == null || read == null) {
        return written == read;
      }
      return ((OffsetDateTime) written).isEqual((OffsetDateTime) read);
    }
  },
  /** The two {@code byte[]} values must be element-wise equal. */
  BYTES_EQUAL {
    @Override
    public boolean equal(@Nullable Object written, @Nullable Object read) {
      if (written == null || read == null) {
        return written == read;
      }
      return Arrays.equals((byte[]) written, (byte[]) read);
    }
  },
  /** The two arrays must be recursively equal, unwrapping every dimension and primitive leaf. */
  DEEP_EQUALS {
    @Override
    public boolean equal(@Nullable Object written, @Nullable Object read) {
      if (written == null || read == null) {
        return written == read;
      }
      // PostgreSQL has no non-empty-dimension empty array: it normalises every empty array to the
      // canonical zero-dimension form ({}), which the codec reads back as a one-dimensional empty
      // array whatever the written rank was. So an empty int4[][][] round-trips to an empty Integer[],
      // and that collapse is the driver's contract, not a mismatch. Treat two empty arrays as equal
      // regardless of declared rank; any non-empty case still requires the same runtime class below.
      if (isEmptyArray(written) && isEmptyArray(read)) {
        return true;
      }
      // The runtime array class must match as well as the contents. For non-empty arrays this catches
      // a text-vs-binary disagreement on dimensionality that Arrays.deepEquals would otherwise hide;
      // deepEquals then covers every dimension and both leaf representations of the contents.
      if (written.getClass() != read.getClass()) {
        return false;
      }
      return Arrays.deepEquals(new Object[]{written}, new Object[]{read});
    }

    // Whether the value is an array holding no leaf elements at any depth (the empty-array form).
    private boolean isEmptyArray(Object value) {
      if (!value.getClass().isArray()) {
        return false;
      }
      int length = Array.getLength(value);
      if (length == 0) {
        return true;
      }
      // A nested array with a non-empty outer dimension is empty only if every sub-array is empty,
      // for instance an int[2][0]. A primitive leaf array of non-zero length is never empty, and a
      // boxed sub-array slot holding a leaf value -- including a SQL NULL -- is a present element, so
      // it makes the array non-empty.
      for (int i = 0; i < length; i++) {
        Object element = Array.get(value, i);
        if (element == null || !isEmptyArray(element)) {
          return false;
        }
      }
      return true;
    }
  };

  /** Whether the value read back matches the value written under this fidelity. */
  public abstract boolean equal(@Nullable Object written, @Nullable Object read);
}
