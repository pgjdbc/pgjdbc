package org.postgresql.jdbc;

import static org.junit.Assert.assertFalse;

import org.junit.Test;
import org.postgresql.core.Oid;
import org.postgresql.util.PSQLException;

import java.math.BigDecimal;
import java.sql.SQLFeatureNotSupportedException;

public class ArraysTest {

  @Test(expected = PSQLException.class)
  public void testNonArrayNotSupported() throws Exception {
    Arrays.getArraySupport("asdflkj");
  }

  @Test(expected = PSQLException.class)
  public void testNoByteArray() throws Exception {
    Arrays.getArraySupport(new byte[] {});
  }

  @Test(expected = SQLFeatureNotSupportedException.class)
  public void testBinaryNotSupported() throws Exception {
    final Arrays.ArraySupport<BigDecimal[]> support = Arrays.getArraySupport(new BigDecimal[] {});

    assertFalse(support.supportBinaryRepresentation(Oid.FLOAT8_ARRAY));

    support.toBinaryRepresentation(null, new BigDecimal[] { BigDecimal.valueOf(3) }, Oid.FLOAT8_ARRAY);
  }
}
