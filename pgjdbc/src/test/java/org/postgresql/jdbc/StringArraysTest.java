/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Oid;

public class StringArraysTest extends AbstractArraysTest<String[]> {

  private static final String[][][] strings = new String[][][] {
      { { "some", "String", "haVE some \u03C0", "another" }, { null, "6L", "7L", "8L" }, //unicode escape for pi character
          { "asdf", " asdf ", "11L", null } },
      { { "13L", null, "asasde4wtq", "16L" }, { "17L", "", "19L", "20L" }, { "21L", "22L", "23L", "24L" } } };

  public StringArraysTest() {
    super(strings, true, Oid.VARCHAR_ARRAY);
  }
}
