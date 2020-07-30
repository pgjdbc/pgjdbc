/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class BooleanArraysTest extends AbstractArraysTest<boolean[]> {
  private static final boolean[][][] booleans = new boolean[][][] {
      { { true, false, false, true }, { false, false, true, true }, { true, true, false, false } },
      { { false, true, true, false }, { true, false, true, false }, { false, true, false, true } } };

  public BooleanArraysTest() {
    super(booleans, true, Oid.BOOL_ARRAY);
  }
}
