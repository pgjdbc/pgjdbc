/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

public class StatementTimeoutTest {
  private static final String schemaName = "statement_timeout_test";
  private static final String optionsValue = "500";

  @Before
  public void setUp() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();
    stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + ";");
    stmt.execute("CREATE SCHEMA " + schemaName + ";");
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  public void testOptionsInProperties() throws Exception {
    System.setProperty("statementTimeout", optionsValue);

    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();

    try {
      stmt.execute("SELECT pg_sleep(1)");
      Assert.fail("This should have failed, since the timeout is 500 milliseconds and we sleep for 1000");
    } catch (SQLException e) {
      Assert.assertEquals("ERROR: canceling statement due to statement timeout", e.getMessage());
    }

    stmt.close();
    TestUtil.closeDB(con);
  }

  @After
  public void tearDown() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();
    stmt.execute("DROP SCHEMA " + schemaName + ";");
    stmt.close();
    TestUtil.closeDB(con);
  }
}
