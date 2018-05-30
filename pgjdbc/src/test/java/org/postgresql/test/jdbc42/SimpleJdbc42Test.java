/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertTrue;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

/**
 * Most basic test to check that the right package is compiled.
 */
public class SimpleJdbc42Test extends BaseTest4 {

  /**
   * Test presence of JDBC 4.2 specific methods.
   */
  @Test
  public void testSupportsRefCursors() throws Exception {
    assertTrue(con.getMetaData().supportsRefCursors());
  }
}
