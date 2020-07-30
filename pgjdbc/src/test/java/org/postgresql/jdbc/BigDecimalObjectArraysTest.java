/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static java.math.BigDecimal.valueOf;

import org.postgresql.core.Oid;

import java.math.BigDecimal;

public class BigDecimalObjectArraysTest extends AbstractArraysTest<BigDecimal[]> {

  private static final BigDecimal[][][] doubles = new BigDecimal[][][] {
      { { valueOf(1.3), valueOf(2.4), valueOf(3.1), valueOf(4.2) },
          { valueOf(5D), valueOf(6D), valueOf(7D), valueOf(8D) },
          { valueOf(9D), valueOf(10D), valueOf(11D), valueOf(12D) } },
      { { valueOf(13D), valueOf(14D), valueOf(15D), valueOf(16D) }, { valueOf(17D), valueOf(18D), valueOf(19D), null },
          { valueOf(21D), valueOf(22D), valueOf(23D), valueOf(24D) } } };

  public BigDecimalObjectArraysTest() {
    super(doubles, false, Oid.NUMERIC_ARRAY);
  }
}
