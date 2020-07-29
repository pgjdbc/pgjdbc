/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class FloatArraysTest extends AbstractArraysTest<float[]> {

  private static final float[][][] floats = new float[][][] {
      { { 1.2f, 2.3f, 3.7f, 4.9f }, { 5, 6, 7, 8 }, { 9, 10, 11, 12 } },
      { { 13, 14, 15, 16 }, { 17, 18, 19, 20 }, { 21, 22, 23, 24 } } };

  public FloatArraysTest() {
    super(floats, true, Oid.FLOAT4_ARRAY);
  }
}
