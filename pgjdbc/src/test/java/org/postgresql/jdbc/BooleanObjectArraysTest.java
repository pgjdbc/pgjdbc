/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

public class BooleanObjectArraysTest extends AbstractArraysTest<Boolean[]> {
  private static final Boolean[][][] booleans = new Boolean[][][] {
      { { true, false, null, true }, { false, false, true, true }, { true, true, false, false } },
      { { false, true, true, false }, { true, false, true, null }, { false, true, false, true } } };

  public BooleanObjectArraysTest() {
    super(booleans, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getExpectedString(Boolean[] expected, char delim) {
    final StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (int i = 0; i < expected.length; ++i) {
      if (i != 0) {
        sb.append(delim);
      }
      if (expected[i] == null) {
        sb.append("NULL");
      } else {
        sb.append(expected[i] ? '1' : '0');
      }
    }
    sb.append('}');
    return sb.toString();
  }
}
