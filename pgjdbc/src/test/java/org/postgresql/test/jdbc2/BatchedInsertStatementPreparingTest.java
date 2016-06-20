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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

public class BatchedInsertStatementPreparingTest extends BaseTest {

  /**
   * Test to check a PreparedStatement has been prepared on the backend.
   */
  public void testStatementPreparedStatusOnBackend() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));

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
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));

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
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));
      // at this point the x_P_2 and the x_P_6 statements have been prepared

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));

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
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));

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
      assertEquals(
          "Expected outcome not returned by batch execution.",
          Arrays.toString(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO }),
          Arrays.toString(pstmt.executeBatch()));
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  public BatchedInsertStatementPreparingTest(String name) {
    super(name);
  }

  /* Set up the fixture for this test case: a connection to a database with
  a table for this test. */
  protected void setUp() throws Exception {
    super.setUp();
    /* Drop the test table if it already exists for some reason. It is
    not an error if it doesn't exist. */
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER");

    /* Generally recommended with batch updates. By default we run all
    tests in this test case with autoCommit disabled. */
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  protected void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testbatch");
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, true);
    forceBinary(props);
  }
}
