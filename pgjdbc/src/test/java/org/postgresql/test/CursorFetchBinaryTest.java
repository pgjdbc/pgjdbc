/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test;

import org.postgresql.test.jdbc2.CursorFetchTest;

import java.util.Properties;

public class CursorFetchBinaryTest extends CursorFetchTest {
  public CursorFetchBinaryTest(String name) {
    super(name);
  }

  @Override
  protected void updateProperties(Properties props) {
    forceBinary(props);
  }
}
