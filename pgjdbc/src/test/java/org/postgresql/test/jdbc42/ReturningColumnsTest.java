/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * This object tests than RETURNING clause is handled properly
 */
public class ReturningColumnsTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "testreturning", "pk INTEGER, col1 INTEGER, col2 text");

    Statement st = con.createStatement();
    st.executeUpdate("INSERT INTO testreturning VALUES (1, 0, 'a')");
    st.close();
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testreturning");
    super.tearDown();
  }

  /**
   * Test for optional behavior when PGProperty.ESCAPE_RETURNING_COLUMNS = false
   */
  @Test
  public void updateReturningEscapingOff() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("update testreturning t set col1 = ?, col2 = ?",
          new String[] {"t.col1 as \"alias1\"", "length(t.col2) as \"alias2\""});
      pstmt.setInt(1, 1);
      pstmt.setString(2, "aaaaa");
      pstmt.execute();
      ResultSet rs = pstmt.getGeneratedKeys();
      Assert.assertTrue("Returning result set is non null", rs != null);
      int returnedRows = 0;
      while (rs.next()) {
        returnedRows++;
        Assert.assertTrue("alias1 value is 1", rs.getInt("alias1") == 1);
        Assert.assertTrue("alias2 value is 5", rs.getInt("alias2") == 5);
      }
      Assert.assertTrue("Returning result set returned a row", returnedRows == 1);
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * Non regression test for default behavior when PGProperty.ESCAPE_RETURNING_COLUMNS = true
   */
  @Test
  public void updateReturningEscapingOn() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("update testreturning t set col1 = ?, col2 = ?",
          new String[] {"col1", "col2"});
      pstmt.setInt(1, 1);
      pstmt.setString(2, "aaaaa");
      pstmt.execute();
      ResultSet rs = pstmt.getGeneratedKeys();
      Assert.assertTrue("Returning result set is non null", rs != null);
      int returnedRows = 0;
      while (rs.next()) {
        returnedRows++;
        Assert.assertTrue("col1 value is 1", rs.getInt("col1") == 1);
        Assert.assertTrue("col2 value is aaaaa", rs.getString("col2").equals("aaaaa"));
      }
      Assert.assertTrue("Returning result set returned a row", returnedRows == 1);
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

}
