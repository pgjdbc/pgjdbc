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

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class BatchedInsertStatementPreparingTest extends TestCase {

  /**
   * Test to check a PreparedStatement has been prepared on the backend.
   */
  public void testStatementPreparedStatusOnBackend() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      /*
       * The connection is configured so the batch rewrite optimization is
       * enabled. Configure a prepare threshold of 1.
       */
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      int[] outcome = pstmt.executeBatch(); // 1st

      assertNotNull(outcome);
      assertEquals(2, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      outcome = pstmt.executeBatch();

      assertNotNull(outcome);
      assertEquals(2, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);

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
      pstmt.setInt(1, 9);
      pstmt.setInt(2, 10);
      pstmt.addBatch();
      outcome = pstmt.executeBatch(); // 1st

      assertNotNull(outcome);
      assertEquals(5, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[2]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[3]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[4]);

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      outcome = pstmt.executeBatch(); // 2nd

      assertNotNull(outcome);
      assertEquals(2, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);

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
      pstmt.setInt(1, 9);
      pstmt.setInt(2, 10);
      pstmt.addBatch();
      outcome = pstmt.executeBatch(); // 2nd

      assertNotNull(outcome);
      assertEquals(5, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[2]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[3]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[4]);
      // at this point the x_P_2 and the x_P_6 statements have been prepared

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      outcome = pstmt.executeBatch(); // 3rd

      assertNotNull(outcome);
      assertEquals(2, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);

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
      pstmt.setInt(1, 9);
      pstmt.setInt(2, 10);
      pstmt.addBatch();
      outcome = pstmt.executeBatch(); // 3rd

      assertNotNull(outcome);
      assertEquals(5, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[2]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[3]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[4]);

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      outcome = pstmt.executeBatch(); // 4th

      assertNotNull(outcome);
      assertEquals(2, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);

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
      pstmt.setInt(1, 9);
      pstmt.setInt(2, 10);
      pstmt.addBatch();
      outcome = pstmt.executeBatch(); // 4th

      assertNotNull(outcome);
      assertEquals(5, outcome.length);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[0]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[1]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[2]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[3]);
      assertEquals(Statement.SUCCESS_NO_INFO, outcome[4]);
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

  private Connection con;

  public BatchedInsertStatementPreparingTest(String name) {
    super(name);
    try {
      Class.forName("org.postgresql.Driver");
    } catch (Exception ex) {
    }
  }

  /* Set up the fixture for this test case: a connection to a database with
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
}
