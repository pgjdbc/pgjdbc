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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

public class BatchedInsertDoubleRowInSingleBatch extends BaseTest {

  /**
   * This is a test for a certain use case. A check to make sure it is not broken.
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
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { 2 },
              pstmt.executeBatch()));
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
    super.setUp();
    Statement stmt = con.createStatement();

    /* Drop the test table if it already exists for some reason. It is
    not an error if it doesn't exist. */
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER");

    stmt.close();

    /* Generally recommended with batch updates. By default we run all
    tests in this test case with autoCommit disabled. */
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  protected void tearDown() throws SQLException {
    con.setAutoCommit(true);

    TestUtil.dropTable(con, "testbatch");
    System.setProperty(PGProperty.REWRITE_BATCHED_INSERTS.getName(),
        PGProperty.REWRITE_BATCHED_INSERTS.getDefaultValue());
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    props.setProperty(PGProperty.REWRITE_BATCHED_INSERTS.getName(),
        Boolean.TRUE.toString());
    props.setProperty(PGProperty.PREPARE_THRESHOLD.getName(), "1");
  }
}
