/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.After;
import org.junit.Before;

import java.sql.SQLException;

public abstract class BaseConnectionTest extends BaseTest4 {

  protected BaseConnection baseConnection;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  @Override
  public void setUp() throws Exception {
    super.setUp();
    baseConnection = con.unwrap(BaseConnection.class);
  }

  // Tear down the fixture for this test case.
  @After
  @Override
  public void tearDown() throws SQLException {
    baseConnection = null;
    super.tearDown();
  }
}
