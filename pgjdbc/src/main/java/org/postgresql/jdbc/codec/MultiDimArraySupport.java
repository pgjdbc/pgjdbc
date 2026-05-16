/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.jdbc.PgArray;
import org.postgresql.util.ByteConverter;
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
    int dims = 0;
    Class<?> cls = array.getClass();
    while (cls.isArray()) {
      dims++;
      cls = cls.getComponentType();
    }
    return dims;
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

  static byte[] zeroDimBinaryArray(int elementOid) {
    byte[] bytes = new byte[12];
    ByteConverter.int4(bytes, 8, elementOid);
    return bytes;
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
