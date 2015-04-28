/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class BatchedInsertDoubleRowInSingleBatch extends TestCase {

  /**
   * This is a test for a corner case. A corner case that is valid however.
   * INSERT INTO testbatch (a,b) VALUES (?,?),(?,?)
   */
  public void testDoubleRowInsert() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?),(?,?)");
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.setInt(3, 3);
      pstmt.setInt(4, 4);
      pstmt.addBatch();
      int[] outcome = pstmt.executeBatch();
      assertNotNull(outcome);
      assertEquals(1, outcome.length);
      assertEquals(2, outcome[0]);
    } catch (SQLException sqle) {
      StringBuilder m = new StringBuilder();
      m.append(sqle.getMessage());
      if (sqle.getCause() != null) {
        m.append(sqle.getCause().getMessage());
      }
      fail("Failed to execute four statements added to a re used Prepared Statement. Reason:"
          + m.toString());
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  public BatchedInsertDoubleRowInSingleBatch(String name) {
    super(name);
    try {
      Class.forName("org.postgresql.Driver");
    } catch (Exception ex) {
    }
  }

  /* Set up the fixture for this testcase: a connection to a database with
  a table for this test. */
  protected void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.REWRITE_BATCHED_INSERTS.getName(),
        Boolean.TRUE.toString());
    props.setProperty(PGProperty.PREPARE_THRESHOLD.getName(), "1");

    con = TestUtil.openDB(props);
    Statement stmt = con.createStatement();

    /* Drop the test table if it already exists for some reason. It is
    not an error if it doesn't exist. */
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER");

    stmt.executeUpdate("INSERT INTO testbatch VALUES (1, 0)");
    stmt.close();

    TestUtil.createTable(con, "prep", "a integer, b integer");

    /* Generally recommended with batch updates. By default we run all
    tests in this test case with autoCommit disabled. */
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  protected void tearDown() throws Exception {
    con.setAutoCommit(true);

    TestUtil.dropTable(con, "testbatch");
    TestUtil.closeDB(con);
    System.setProperty(PGProperty.REWRITE_BATCHED_INSERTS.getName(),
        PGProperty.REWRITE_BATCHED_INSERTS.getDefaultValue());
  }

  private Connection con;
}
