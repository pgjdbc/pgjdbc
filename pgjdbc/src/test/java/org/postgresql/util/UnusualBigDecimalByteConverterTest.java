/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.math.BigDecimal;

/**
 * Tests unusual binary representations of numeric values.
 * @author Brett Okken
 */
public class UnusualBigDecimalByteConverterTest {

  /**
   * Typically a number < 1 would have sections of leading '0' values represented in weight
   * rather than including as short values.
   */
  @Test
  public void test_4_leading_0() {
    //len 2
    //weight -1
    //scale 5
    final byte[] data = new byte[] {0, 2, -1, -1, 0, 0, 0, 5, 0, 0, 23, 112};
    final BigDecimal actual = (BigDecimal) ByteConverter.numeric(data);
    assertEquals(new BigDecimal("0.00006"), actual);
  }
}
