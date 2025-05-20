/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.Oid;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLFeatureNotSupportedException;

class ArraysTest {

  @Test
  void nonArrayNotSupported() throws Exception {
    assertThrows(PSQLException.class, () -> {
      ArrayEncoding.getArrayEncoder("asdflkj");
    });
  }

  @Test
  void noByteArray() throws Exception {
    assertThrows(PSQLException.class, () -> {
      ArrayEncoding.getArrayEncoder(new byte[]{});
    });
  }

  @Test
  void binaryNotSupported() throws Exception {
    assertThrows(SQLFeatureNotSupportedException.class, () -> {
      final ArrayEncoding.ArrayEncoder<BigDecimal[]> support = ArrayEncoding.getArrayEncoder(new BigDecimal[]{});

      assertFalse(support.supportBinaryRepresentation(Oid.FLOAT8_ARRAY));

      support.toBinaryRepresentation(null, new BigDecimal[]{BigDecimal.valueOf(3)}, Oid.FLOAT8_ARRAY);
    });
  }

  @Test
  void validateRegularRectangularArray() throws PSQLException {
    int[][] regular = {
        {1, 2, 3},
        {4, 5, 6},
        {7, 8, 9}
    };
    ArrayEncoding.validateRectangular(regular);
  }

  @Test
  void validateEmptyArray() throws PSQLException {
    int[][] empty = new int[0][0];
    ArrayEncoding.validateRectangular(empty);
  }

  @Test
  void validateJaggedArray() {
    int[][] jagged = {
        {1, 2, 3},
        {4, 5},
        {7, 8, 9}
    };
    assertThrows(PSQLException.class, () -> {
      ArrayEncoding.validateRectangular(jagged);
    });
  }

  @Test
  void validateThreeDimensionalArray() throws PSQLException {
    int[][][] regular3D = {
        {{1, 2}, {3, 4}},
        {{5, 6}, {7, 8}}
    };
    ArrayEncoding.validateRectangular(regular3D);
  }

  @Test
  void validateJaggedThreeDimensionalArray() {
    int[][][] jagged3D = {
        {{1, 2}, {3}},
        {{5, 6}, {7, 8}}
    };
    assertThrows(PSQLException.class, () -> {
      ArrayEncoding.validateRectangular(jagged3D);
    });
  }

  @Test
  void nullArraysAreValid() {
    assertDoesNotThrow(() -> ArrayEncoding.validateRectangular(null));
  }

  @Test
  void validateArrayWithNullElements() throws PSQLException {
    Integer[][] arrayWithNulls = {
        {1, null, 3},
        {4, 5, 6},
        {7, 8, 9}
    };
    ArrayEncoding.validateRectangular(arrayWithNulls);
  }

  @Test
  void testNestedNullSubarrays() throws PSQLException {
    Long[][] array = {{1L, 2L}, null, {3L, 4L}};
    assertDoesNotThrow(() -> ArrayEncoding.validateRectangular(array));
  }
}
