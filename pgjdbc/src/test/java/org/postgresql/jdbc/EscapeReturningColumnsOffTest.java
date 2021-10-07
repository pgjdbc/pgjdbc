/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * This object tests than RETURNING clause is handled properly when escapeReturningColumns is false
 */
public class EscapeReturningColumnsOffTest extends BaseTest4 {

  /*
   * Set up the fixture for this testcase: a connection to a database with a
   * table for this test.
   */
  @Override
  public void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();

    /*
     * Drop the test table if it already exists for some reason. It is not an
     * error if it doesn't exist.
     */
    TestUtil.createTable(con, "testreturning", "pk INTEGER, col1 INTEGER, col2 INTEGER");

    stmt.executeUpdate("INSERT INTO testreturning VALUES (1, 0, 0)");
    stmt.close();

    /*
     * Generally recommended with batch updates. By default we run all tests in
     * this test case with autoCommit disabled.
     */
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testreturning");
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    PGProperty.ESCAPE_RETURNING_COLUMNS.set(props, false);
  }

  /**
   * Test for optional behavior when PGProperty.ESCAPE_RETURNING_COLUMNS = false
   */
  @Test
  public void updateReturningEscapingOff() throws SQLException {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement)con.prepareStatement("update testreturning t set col1 = ?, col2 = ?",
          new String[] {"t.col1 as alias1", "t.col2 as alias2"});
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.execute();
      ResultSet rs = pstmt.getGeneratedKeys();
      Assert.assertTrue("Returning result set is non null", rs != null);
      int returnedRows = 0;
      while (rs.next()) {
        returnedRows++;
        Assert.assertTrue("alias1 value is 1", rs.getInt("alias1") == 1);
        Assert.assertTrue("alias2 value is 2", rs.getInt("alias2") == 2);
      }
      Assert.assertTrue("Returning result set returned a row", returnedRows == 1);
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

}
