/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

/**
 * Unit tests for the array shape helpers, focused on the {@code bytea} case where
 * one SQL element is itself a Java {@code byte[]}. A {@code byte[][]} is a
 * one-dimensional {@code bytea[]} of {@code byte[]} leaves, not a two-dimensional
 * array, so its elements may have different lengths — unlike a genuine
 * multidimensional array, which must be rectangular.
 */
class MultiDimArraySupportTest {

  // ---------------- computeDimensions ----------------

  @Test
  void computeDimensions_byteaIsLeafAware() {
    // byte[] element treated as a leaf: byte[][] is bytea[] (1-D), byte[][][] is bytea[][] (2-D).
    assertEquals(1, MultiDimArraySupport.computeDimensions(new byte[2][], byte[].class));
    assertEquals(2, MultiDimArraySupport.computeDimensions(new byte[2][3][], byte[].class));
    // The outermost level is always a dimension, so a bare byte[] stays 1-D.
    assertEquals(1, MultiDimArraySupport.computeDimensions(new byte[4], byte[].class));
  }

  @Test
  void computeDimensions_syntacticWhenLeafNotArray() {
    // Without (or with a non-array) leaf class, the count is purely syntactic.
    assertEquals(2, MultiDimArraySupport.computeDimensions(new byte[2][3]));
    assertEquals(2, MultiDimArraySupport.computeDimensions(new int[2][3], Integer.class));
    assertEquals(2, MultiDimArraySupport.computeDimensions(new Integer[2][3], Integer.class));
    assertEquals(1, MultiDimArraySupport.computeDimensions(new String[3], String.class));
  }

  @Test
  void computeDimensions_runtimeNestingUnderObjectComponent() {
    // The declared type is Object[], but the runtime elements are arrays, so the value is
    // multidimensional at runtime (as createArrayOf("int4", new Object[]{new Object[]{1, 2}}) makes).
    assertEquals(2, MultiDimArraySupport.computeDimensions(
        new Object[]{new Object[]{1, 2}}, Integer.class));
    assertEquals(2, MultiDimArraySupport.computeDimensions(
        new Object[]{new Integer[]{1, 2}}, Integer.class));
    assertEquals(3, MultiDimArraySupport.computeDimensions(
        new Object[]{new Object[]{new Object[]{1}}}, Integer.class));
    // Ragged runtime nesting still reports the first-element depth; rectangularity is enforced
    // separately by validateRectangular.
    assertEquals(2, MultiDimArraySupport.computeDimensions(
        new Object[]{new Object[]{1, 2}, new Object[]{3}}, Integer.class));
    // Serializable[]/Cloneable[] are the other reference types an array instance fits into.
    assertEquals(2, MultiDimArraySupport.computeDimensions(
        new java.io.Serializable[]{new Integer[]{1, 2}}, Integer.class));
  }

  @Test
  void computeDimensions_objectComponentScalarsStayOneDimensional() {
    // Object[] of scalars (the common createArrayOf("int4", new Object[]{1, 2, 3}) shape) is 1-D.
    assertEquals(1, MultiDimArraySupport.computeDimensions(new Object[]{1, 2, 3}, Integer.class));
    // Empty or null-first arrays cannot be probed deeper and stay at the by-class count.
    assertEquals(1, MultiDimArraySupport.computeDimensions(new Object[0], Integer.class));
    assertEquals(1, MultiDimArraySupport.computeDimensions(
        new Object[]{null, new Object[]{1}}, Integer.class));
  }

  @Test
  void computeDimensions_runtimeNestingRespectsByteaLeaf() {
    // An Object[] of byte[] is a 1-D bytea[]: the byte[] elements are leaves, not a dimension.
    assertEquals(1, MultiDimArraySupport.computeDimensions(
        new Object[]{new byte[]{1}, new byte[]{2, 3}}, byte[].class));
    // An Object[] of Object[] of byte[] is a 2-D bytea[][].
    assertEquals(2, MultiDimArraySupport.computeDimensions(
        new Object[]{new Object[]{new byte[]{1}}}, byte[].class));
  }

  // ---------------- validateJavaArray ----------------

  @Test
  void validateJavaArray_jaggedByteaAllowedWithLeafClass() {
    // bytea[] with differing element lengths and a NULL element: valid.
    byte[][] in = {{0x01, (byte) 0xFF, 0x12}, {}, {(byte) 0xAC, (byte) 0xE4}, null};
    assertDoesNotThrow(() -> ArrayCodec.validateJavaArray(in, byte[].class));
  }

  @Test
  void validateJavaArray_jaggedByteaRejectedWithoutLeafClass() {
    // The pre-existing bug: without the leaf class, byte[][] is read as a jagged
    // 2-D array and rejected.
    byte[][] in = {{0x01, 0x02, 0x03}, {}};
    assertThrows(SQLException.class, () -> ArrayCodec.validateJavaArray(in, null));
  }

  @Test
  void validateJavaArray_realMultidimStillValidated() {
    // A genuine 2-D array must still be rectangular regardless of the leaf class.
    int[][] jagged = {{1, 2}, {3}};
    assertThrows(SQLException.class, () -> ArrayCodec.validateJavaArray(jagged, Integer.class));
    assertDoesNotThrow(() -> ArrayCodec.validateJavaArray(new int[][]{{1, 2}, {3, 4}}, Integer.class));
  }

  @Test
  void validateJavaArray_nestedByteaRejectsNullSubarray() throws SQLException {
    // bytea[][] (byte[][][]) is 2-D: a null inner bytea[] is a null sub-array.
    byte[][][] withNullInner = {{{0x01}}, null};
    assertThrows(SQLException.class, () -> ArrayCodec.validateJavaArray(withNullInner, byte[].class));
    // ... but a null byte[] *leaf* inside a sub-array is fine.
    byte[][][] withNullLeaf = {{{0x01}, null}, {{0x02}, {0x03}}};
    assertDoesNotThrow(() -> ArrayCodec.validateJavaArray(withNullLeaf, byte[].class));
  }
}
