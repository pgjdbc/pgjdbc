/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

public class BatchedInsertReWriteEnabledTest extends BaseTest {

  /**
   * Check batching using two individual statements that are both the same type.
   * Test to check the re-write optimisation behaviour.
   */
  public void testBatchWithReWrittenRepeatedInsertStatementOptimizationEnabled()
      throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));

      /*
       * Now check the ps can be reused. The batched statement should be reset
       * and have no knowledge of prior re-written batch. This test uses a
       * different batch size. To test if the driver detects the different size
       * and prepares the statement on with the backend. If not then an
       * exception will be thrown for an unknown prepared statement.
       */
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      pstmt.setInt(1, 7);
      pstmt.setInt(2, 8);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      pstmt.setInt(1, 7);
      pstmt.setInt(2, 8);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));

    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
    }
  }

  /**
   * Check batching using a statement with fixed parameter.
   */
  public void testBatchWithReWrittenBatchStatementWithFixedParameter()
      throws SQLException {
    String[] odd = new String[]{
        "INSERT INTO testbatch VALUES (?, '1, (, $1234, a''n?d )' /*xxxx)*/, ?) -- xxx",
        // "INSERT /*xxx*/INTO testbatch VALUES (?, '1, (, $1234, a''n?d )' /*xxxx)*/, ?) -- xxx",
    };
    for (int i = 0; i < odd.length; i++) {
      String s = odd[i];
      PreparedStatement pstmt = null;
      try {
        pstmt = con.prepareStatement(s);
        pstmt.setInt(1, 1);
        pstmt.setInt(2, 2);
        pstmt.addBatch();
        pstmt.setInt(1, 3);
        pstmt.setInt(2, 4);
        pstmt.addBatch();
        pstmt.setInt(1, 5);
        pstmt.setInt(2, 6);
        pstmt.addBatch();
        assertTrue(
            "Expected outcome not returned by batch execution.",
            Arrays.equals(new int[]{Statement.SUCCESS_NO_INFO,
                Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO},
                pstmt.executeBatch()));
        con.commit();
      } finally {
        if (null != pstmt) {
          pstmt.close();
        }
      }
    }
  }

  /**
   * Test to make sure a statement with a semicolon is not broken
   */
  public void testBatchWithReWrittenBatchStatementWithSemiColon()
      throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?,?);");
      pstmt.setInt(1, 1);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setString(2, "b");
      pstmt.setInt(3, 4);
      pstmt.addBatch();
      pstmt.setInt(1, 5);
      pstmt.setString(2, "c");
      pstmt.setInt(3, 6);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  /**
   * Test case to check the outcome for a batch with a single row/batch is
   * consistent across calls to executeBatch. Especially after a batch
   * has been re-written.
   */
  public void testConsistentOutcome() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?,?);");
      pstmt.setInt(1, 1);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { 1 }, pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setString(2, "b");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setString(2, "c");
      pstmt.setInt(3, 4);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }, pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setString(2, "d");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { 1 }, pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  /**
   * Test to check statement with named columns still work as expected.
   */
  public void testINSERTwithNamedColumnsNotBroken() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con
          .prepareStatement("INSERT INTO testbatch (pk, col1, col2) VALUES (?,?,?);");
      pstmt.setInt(1, 1);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { 1 }, pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  public void testMixedCaseInSeRtStatement() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con
          .prepareStatement("InSeRt INTO testbatch VALUES (?,?,?);");
      pstmt.setInt(1, 1);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setString(2, "b");
      pstmt.setInt(3, 4);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution. Meaning the driver"
          + "did not detect the INSERT statement with mixed case keyword.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }, pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  public void testReWriteDisabledForPlainBatch() throws Exception {
    Statement stmt = null;
    Connection conn = null;
    try {
      conn = TestUtil.openDB(new Properties());
      stmt = conn.createStatement();
      stmt.addBatch("INSERT INTO testbatch VALUES (100,'a',200);");
      stmt.addBatch("INSERT INTO testbatch VALUES (300,'b',400);");
      assertTrue(
          "Expected outcome not returned by batch execution. The driver"
              + " allowed re-write in combination with plain statements.",
          Arrays.equals(new int[]{1, 1}, stmt.executeBatch()));
    } finally {
      if (null != stmt) {
        stmt.close();
      }
      if (null != conn) {
        conn.close();
      }
    }
  }

  public void testReWriteDisabledForAutoCommitConnections() throws Exception {
    PreparedStatement pstmt = null;
    Connection autocommit = null;
    try {
      autocommit = TestUtil.openDB(new Properties());
      autocommit.setAutoCommit(true);
      pstmt = autocommit
          .prepareStatement("INSERT INTO testbatch VALUES (?,?,?);");
      pstmt.setInt(1, 100);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 200);
      pstmt.addBatch();
      pstmt.setInt(1, 300);
      pstmt.setString(2, "b");
      pstmt.setInt(3, 400);
      pstmt.addBatch();
      assertTrue(
          "Expected outcome not returned by batch execution. The driver"
          + " allowed re-write in combination with a connection configured"
          + " to autocommit.",
          Arrays.equals(new int[] { 1, 1 }, pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      if (null != autocommit) {
        autocommit.close();
      }
    }
  }

  public BatchedInsertReWriteEnabledTest(String name) {
    super(name);
    try {
      Class.forName("org.postgresql.Driver");
    } catch (Exception ex) {
    }
  }

  /* Set up the fixture for this testcase: a connection to a database with
  a table for this test. */
  protected void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();

    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 VARCHAR, col2 INTEGER");

    stmt.close();

    /* Generally recommended with batch updates. By default we run all
    tests in this test case with autoCommit disabled. */
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  protected void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testbatch");
    System.setProperty(PGProperty.REWRITE_BATCHED_INSERTS.getName(),
        PGProperty.REWRITE_BATCHED_INSERTS.getDefaultValue());
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    props.setProperty(PGProperty.REWRITE_BATCHED_INSERTS.getName(),
        Boolean.TRUE.toString());
  }
}
