/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

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
}
