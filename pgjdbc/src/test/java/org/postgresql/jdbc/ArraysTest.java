/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertFalse;

import org.postgresql.core.Oid;
import org.postgresql.util.PSQLException;

import org.junit.Test;

import java.math.BigDecimal;
import java.sql.SQLFeatureNotSupportedException;

public class ArraysTest {

  @Test(expected = PSQLException.class)
  public void testNonArrayNotSupported() throws Exception {
    ArrayEncoding.getArrayEncoder("asdflkj");
  }

  @Test(expected = PSQLException.class)
  public void testNoByteArray() throws Exception {
    ArrayEncoding.getArrayEncoder(new byte[] {});
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testBinaryNotSupported() throws Exception {
    final ArrayEncoding.ArrayEncoder<BigDecimal[]> support = ArrayEncoding.getArrayEncoder(new BigDecimal[] {});

    assertFalse(support.supportBinaryRepresentation(Oid.FLOAT8_ARRAY));

    support.toBinaryRepresentation(null, new BigDecimal[] { BigDecimal.valueOf(3) }, Oid.FLOAT8_ARRAY);
  }
}
