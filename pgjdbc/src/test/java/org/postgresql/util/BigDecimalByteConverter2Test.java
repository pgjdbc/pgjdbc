/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.junit.Test;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * This test is moved from {@link BigDecimalByteConverterTest} to prevent test with very long name.
 */
public class BigDecimalByteConverter2Test {
  @Test
  public void testBigDecimal10_pow_131072_minus_1() {
    BigDecimalByteConverterTest.testBinary(
        new BigDecimal(BigInteger.TEN.pow(131072).subtract(BigInteger.ONE))
    );
  }
}
