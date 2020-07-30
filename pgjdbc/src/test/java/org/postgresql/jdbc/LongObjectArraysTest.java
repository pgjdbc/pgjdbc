/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class LongObjectArraysTest extends AbstractArraysTest<Long[]> {

  private static final Long[][][] longs = new Long[][][] {
      { { 1L, 2L, null, 4L }, { 5L, 6L, 7L, 8L }, { 9L, 10L, 11L, 12L } },
      { { 13L, 14L, 15L, 16L }, { 17L, 18L, 19L, 20L }, { 21L, 22L, 23L, 24L } } };

  public LongObjectArraysTest() {
    super(longs, true, Oid.INT8_ARRAY);
  }
}
