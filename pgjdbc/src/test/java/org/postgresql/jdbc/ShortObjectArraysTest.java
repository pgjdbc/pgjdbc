/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class ShortObjectArraysTest extends AbstractArraysTest<Short[]> {

  private static final Short[][][] shorts = new Short[][][] { { { 1, 2, 3, 4 }, { 5, 6, 7, 8 }, { 9, 10, 11, 12 } },
      { { 13, 14, 15, 16 }, { 17, 18, null, 20 }, { 21, 22, 23, 24 } } };

  public ShortObjectArraysTest() {
    super(shorts, true, Oid.INT2_ARRAY);
  }
}
