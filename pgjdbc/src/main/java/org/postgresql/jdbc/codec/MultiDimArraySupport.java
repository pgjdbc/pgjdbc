/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.jdbc.PgArray;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Array;
import java.sql.SQLException;

/**
 * Shared shape helpers for Java arrays encoded as PostgreSQL arrays.
 */
final class MultiDimArraySupport {

  private MultiDimArraySupport() {
    // Utility class
  }

  static int computeDimensions(Object array) {
    return computeDimensions(array, null);
  }

  /**
   * Counts the array dimensions of {@code array}, treating {@code leafElementClass}
   * as a scalar element even when it is itself an array type.
   *
   * <p>PostgreSQL {@code bytea} maps to a Java {@code byte[]} element, so a
   * {@code byte[][]} is a one-dimensional {@code bytea[]} (its elements are
   * {@code byte[]}), not a two-dimensional array. Passing {@code byte[].class}
   * stops the walk at that level so the element {@code byte[]} is not mistaken
   * for an inner dimension; the elements may then have different lengths, which
   * a multidimensional array may not. The outermost level is always a dimension,
   * so a bare {@code byte[]} stays one-dimensional. A {@code null} or non-array
   * {@code leafElementClass} reproduces the plain syntactic count.</p>
   *
   * <p>When the declared component type is an {@code Object}-like reference type
   * ({@code Object}, {@link java.io.Serializable} or {@link Cloneable}) — the
   * only non-array types that can hold an array instance — the static walk
   * cannot see further nesting, yet the runtime elements may themselves be
   * arrays. A {@code createArrayOf("int4", new Object[]{new Object[]{1, 2}})}
   * value is an {@code Object[]} by class but a two-dimensional {@code int4[][]}
   * at runtime. The count is then resumed at runtime by following the first
   * element down to the declared-leaf level and on through any further array
   * nesting (stopping at {@code leafElementClass}). Typed reference arrays
   * ({@code Integer[]}, {@code String[]}, ...) and primitive arrays never reach
   * this branch, so they keep the cheap by-class count.</p>
   */
  static int computeDimensions(Object array, @Nullable Class<?> leafElementClass) {
    int dims = 0;
    Class<?> cls = array.getClass();
    while (cls.isArray()) {
      if (dims >= 1 && cls == leafElementClass) {
        // An array-typed SQL element (a byte[] for bytea): stop, it is a leaf.
        return dims;
      }
      dims++;
      cls = cls.getComponentType();
    }
    if (dims >= 1
        && (cls == Object.class || cls == Cloneable.class || cls == java.io.Serializable.class)) {
      dims += runtimeNestingDepth(array, dims, leafElementClass);
    }
    return dims;
  }

  /**
   * Counts array nesting that an {@code Object}-like declared element type hides.
   * Descends {@code knownDims} levels to the first declared-leaf element, then
   * keeps following the first element while it is itself an array other than
   * {@code leafElementClass}. Returns the number of extra dimensions; {@code 0}
   * when the leaf is a scalar, a probed array is empty, or a probed element is
   * {@code null} (the shape cannot be observed any deeper, and such values are
   * either genuinely one-dimensional or get rejected later by
   * {@link #validateRectangular}).
   */
  private static int runtimeNestingDepth(Object array, int knownDims,
      @Nullable Class<?> leafElementClass) {
    Object cursor = array;
    for (int d = 0; d < knownDims; d++) {
      Object first = firstElementOrNull(cursor);
      if (first == null) {
        return 0;
      }
      cursor = first;
    }
    int extra = 0;
    Class<?> cls = cursor.getClass();
    while (cls.isArray() && cls != leafElementClass) {
      extra++;
      Object first = firstElementOrNull(cursor);
      if (first == null) {
        break;
      }
      cursor = first;
      cls = cursor.getClass();
    }
    return extra;
  }

  /**
   * Returns element {@code [0]} of the array {@code array}, or {@code null} when it is empty or its
   * first element is {@code null} (both meaning the shape cannot be probed deeper). The descent runs
   * only on {@code Object}-like reference arrays, whose levels are themselves {@code Object[]}, so the
   * direct cast avoids the cost of {@link java.lang.reflect.Array}; a primitive leaf array reached as
   * an element ({@code int[]} inside an {@code Object[]}) takes the reflective fallback.
   */
  private static @Nullable Object firstElementOrNull(Object array) {
    if (array instanceof Object[]) {
      Object[] a = (Object[]) array;
      return a.length == 0 ? null : a[0];
    }
    return java.lang.reflect.Array.getLength(array) == 0
        ? null : java.lang.reflect.Array.get(array, 0);
  }

  static @Nullable Object unwrapArrayValue(Object value) throws SQLException {
    if (value instanceof PgArray) {
      return ((PgArray) value).getArray();
    }
    if (value instanceof Array) {
      return ((Array) value).getArray();
    }
    if (value.getClass().isArray()) {
      return value;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  static Class<?> leafComponentType(Class<?> arrayClass) {
    Class<?> c = arrayClass;
    while (c.isArray()) {
      c = c.getComponentType();
    }
    return c;
  }

  /**
   * Whether an array with these dimension lengths holds no elements — a zero length in any
   * dimension. PostgreSQL has no non-empty-dimension empty array: it normalises every such value to
   * the zero-dimension empty form ({@code {}}) and rejects a text literal like {@code {{},{}}}. The
   * encoders use this to emit that canonical shape, so a value whose Java class is
   * multi-dimensional but empty ({@code int[0][]}, {@code int[2][0]}) round-trips through the same
   * one shape in both formats instead of a positive-dimension header the text {@code {}} literal
   * cannot reproduce.
   */
  static boolean isEmpty(int[] dimLengths) {
    for (int length : dimLengths) {
      if (length == 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Computes lengths for each dimension by following the {@code [0]} sub-array
   * at each level, then verifies every sub-array has the same rectangular
   * shape.
   */
  static int[] computeDimensionLengths(Object array, int dimensions) throws SQLException {
    int[] lengths = new int[dimensions];
    Object cursor = array;
    for (int d = 0; d < dimensions; d++) {
      int len = java.lang.reflect.Array.getLength(cursor);
      lengths[d] = len;
      if (d + 1 < dimensions && len > 0) {
        cursor = java.lang.reflect.Array.get(cursor, 0);
      }
    }
    validateRectangular(array, lengths, 0);
    return lengths;
  }

  static void validateRectangular(Object array, int[] lengths, int depth) throws SQLException {
    if (array == null) {
      throw new PSQLException(
          GT.tr("Multidimensional arrays must not contain null sub-arrays"),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    int length = java.lang.reflect.Array.getLength(array);
    if (length != lengths[depth]) {
      throw new PSQLException(
          GT.tr("Multidimensional arrays must be rectangular"),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    if (depth + 1 == lengths.length) {
      return;
    }
    for (int i = 0; i < length; i++) {
      validateRectangular(java.lang.reflect.Array.get(array, i), lengths, depth + 1);
    }
  }
}
