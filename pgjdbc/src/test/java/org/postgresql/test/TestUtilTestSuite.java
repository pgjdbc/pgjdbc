/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TestUtilTestSuite extends BaseTest4 {
  private static final Logger LOGGER = Logger.getLogger(TestUtilTestSuite.class.getName());

  @Test
  public void testLogResultSet() throws SQLException {
    String sql = "SELECT * FROM pg_settings LIMIT 1";
    Statement stmt = con.createStatement();
    stmt.execute(sql);
    ResultSet rs = stmt.getResultSet();
    LOGGER.setLevel(Level.INFO);
    TestUtil.logResultSet(LOGGER, Level.INFO, rs);
  }
}
