/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class DoubleObjectArraysTest extends AbstractArraysTest<Double[]> {

  private static final Double[][][] doubles = new Double[][][] {
      { { 1.3, 2.4, 3.1, 4.2 }, { 5D, 6D, 7D, 8D }, { 9D, 10D, 11D, 12D } },
      { { 13D, 14D, 15D, 16D }, { 17D, 18D, 19D, null }, { 21D, 22D, 23D, 24D } } };

  public DoubleObjectArraysTest() {
    super(doubles, true, Oid.FLOAT8_ARRAY);
  }
}
