/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class FloatObjectArraysTest extends AbstractArraysTest<Float[]> {

  private static final Float[][][] floats = new Float[][][] {
      { { 1.3f, 2.4f, 3.1f, 4.2f }, { 5f, 6f, 7f, 8f }, { 9f, 10f, 11f, 12f } },
      { { 13f, 14f, 15f, 16f }, { 17f, 18f, 19f, null }, { 21f, 22f, 23f, 24f } } };

  public FloatObjectArraysTest() {
    super(floats, true, Oid.FLOAT4_ARRAY);
  }
}
