/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;

/**
 * Most basic test to check that the right package is compiled
 */
public class SimpleJdbc42Test {

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(_conn);
  }

  /**
   * Test presence of JDBC 4.2 specific methods
   */
  @Test
  public void testSupportsRefCursors() throws Exception {
    assertTrue(_conn.getMetaData().supportsRefCursors());
  }
}
