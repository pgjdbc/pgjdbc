/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class BooleanObjectArraysTest extends AbstractArraysTest<Boolean[]> {
  private static final Boolean[][][] booleans = new Boolean[][][] {
      { { true, false, null, true }, { false, false, true, true }, { true, true, false, false } },
      { { false, true, true, false }, { true, false, true, null }, { false, true, false, true } } };

  public BooleanObjectArraysTest() {
    super(booleans, true, Oid.BOOL_ARRAY);
  }
}
