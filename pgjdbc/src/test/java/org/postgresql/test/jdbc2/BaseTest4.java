/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.junit.After;
import org.junit.Before;

import java.sql.SQLException;

public class BaseTest4 extends AbstractBaseTest {
  @Before
  public void setUp() throws Exception {
    super.setUp();
  }

  @After
  public void tearDown() throws SQLException {
    super.tearDown();
  }
}
