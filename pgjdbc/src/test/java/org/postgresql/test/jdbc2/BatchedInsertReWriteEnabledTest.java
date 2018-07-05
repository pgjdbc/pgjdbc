/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class BatchedInsertReWriteEnabledTest extends BaseTest4 {
  private final AutoCommit autoCommit;

  public BatchedInsertReWriteEnabledTest(AutoCommit autoCommit,
      BinaryMode binaryMode) {
    this.autoCommit = autoCommit;
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "{index}: autoCommit={0}, binary={1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (AutoCommit autoCommit : AutoCommit.values()) {
      for (BinaryMode binaryMode : BinaryMode.values()) {
        ids.add(new Object[]{autoCommit, binaryMode});
      }
    }
    return ids;
  }

  /* Set up the fixture for this testcase: a connection to a database with
  a table for this test. */
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 VARCHAR, col2 INTEGER");
    con.setAutoCommit(autoCommit == AutoCommit.YES);
  }

  // Tear down the fixture for this test case.
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testbatch");
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, true);
  }

  /**
   * Check batching using two individual statements that are both the same type.
   * Test to check the re-write optimisation behaviour.
   */

  @Test
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
      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 3, "3 rows inserted");

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
      BatchExecuteTest.assertSimpleInsertBatch(4, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 7, "3+4 rows inserted");

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
      BatchExecuteTest.assertSimpleInsertBatch(4, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 11, "3+4+4 rows inserted");

    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * Check batching using a statement with fixed parameter.
   */
  @Test
  public void testBatchWithReWrittenBatchStatementWithFixedParameter()
      throws SQLException {
    String[] odd = new String[]{
        "INSERT INTO testbatch VALUES (?, '1, (, $1234, a''n?d )' /*xxxx)*/, ?) -- xxx",
        // "INSERT /*xxx*/INTO testbatch VALUES (?, '1, (, $1234, a''n?d )' /*xxxx)*/, ?) -- xxx",
    };
    for (String s : odd) {
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
        BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());
        TestUtil.assertNumberOfRows(con, "testbatch", 3, "3 rows inserted");
      } finally {
        TestUtil.closeQuietly(pstmt);
      }
    }
  }

  /**
   * Check batching using a statement with fixed parameters only.
   */
  @Test
  public void testBatchWithReWrittenBatchStatementWithFixedParametersOnly()
      throws SQLException {
    String[] odd = new String[]{
        "INSERT INTO testbatch VALUES (9, '1, (, $1234, a''n?d )' /*xxxx)*/, 7) -- xxx",
        // "INSERT /*xxx*/INTO testbatch VALUES (?, '1, (, $1234, a''n?d )' /*xxxx)*/, ?) -- xxx",
    };
    for (String s : odd) {
      PreparedStatement pstmt = null;
      try {
        pstmt = con.prepareStatement(s);
        pstmt.addBatch();
        pstmt.addBatch();
        pstmt.addBatch();
        BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());
        TestUtil.assertNumberOfRows(con, "testbatch", 3, "3 rows inserted");
      } finally {
        TestUtil.closeQuietly(pstmt);
      }
    }
  }

  /**
   * Test to make sure a statement with a semicolon is not broken.
   */
  private void simpleRewriteBatch(String values, String suffix)
      throws SQLException {
    PreparedStatement pstmt = null;
    try {
      PreparedStatement clean = con.prepareStatement("truncate table testbatch");
      clean.execute();
      clean.close();

      pstmt = con.prepareStatement("INSERT INTO testbatch " +  values + "(?,?,?)" + suffix);
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
      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 3, "3 rows inserted");
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * Test to make sure a statement with a semicolon is not broken.
   */
  @Test
  public void testBatchWithReWrittenBatchStatementWithSemiColon()
      throws SQLException {
    simpleRewriteBatch("values", ";");
  }

  /**
   * Test to make sure a statement with a semicolon is not broken.
   */
  @Test
  public void testBatchWithReWrittenSpaceAfterValues()
      throws SQLException {
    simpleRewriteBatch("values ", "");
    simpleRewriteBatch("values  ", "");
    simpleRewriteBatch("values\t", "");
  }

  /**
   * Test VALUES word with mixed case.
   */
  @Test
  public void testBatchWithReWrittenMixedCaseValues()
      throws SQLException {
    simpleRewriteBatch("vAlues", "");
    simpleRewriteBatch("vaLUES", "");
    simpleRewriteBatch("VALUES", "");
  }

  /**
   * Test to make sure a statement with a semicolon is not broken.
   */
  @Test
  public void testBindsInNestedParens()
      throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES ((?),((?)),?);");
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
      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * Test to make sure a statement with a semicolon is not broken.
   */
  @Test
  public void testMultiValues1bind()
      throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch (pk) VALUES (?), (?)");
      pstmt.setInt(1, 100);
      pstmt.setInt(2, 200);
      pstmt.addBatch();
      pstmt.setInt(1, 300);
      pstmt.setInt(2, 400);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(2, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * Test case to check the outcome for a batch with a single row/batch is
   * consistent across calls to executeBatch. Especially after a batch
   * has been re-written.
   */
  @Test
  public void testConsistentOutcome() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?,?);");
      pstmt.setInt(1, 1);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(1, pstmt.executeBatch());

      pstmt.setInt(1, 1);
      pstmt.setString(2, "b");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setString(2, "c");
      pstmt.setInt(3, 4);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(2, pstmt.executeBatch());

      pstmt.setInt(1, 1);
      pstmt.setString(2, "d");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(1, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * Test to check statement with named columns still work as expected.
   */
  @Test
  public void testINSERTwithNamedColumnsNotBroken() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con
          .prepareStatement("INSERT INTO testbatch (pk, col1, col2) VALUES (?,?,?);");
      pstmt.setInt(1, 1);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(1, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  @Test
  public void testMixedCaseInSeRtStatement() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("InSeRt INTO testbatch VALUES (?,?,?);");
      pstmt.setInt(1, 1);
      pstmt.setString(2, "a");
      pstmt.setInt(3, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setString(2, "b");
      pstmt.setInt(3, 4);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(2, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  @Test
  public void testReWriteDisabledForPlainBatch() throws Exception {
    Statement stmt = null;
    try {
      con = TestUtil.openDB(new Properties());
      stmt = con.createStatement();
      stmt.addBatch("INSERT INTO testbatch VALUES (100,'a',200);");
      stmt.addBatch("INSERT INTO testbatch VALUES (300,'b',400);");
      Assert.assertEquals(
          "Expected outcome not returned by batch execution. The driver"
              + " allowed re-write in combination with plain statements.",
          Arrays.toString(new int[]{1, 1}), Arrays.toString(stmt.executeBatch()));
    } finally {
      TestUtil.closeQuietly(stmt);
    }
  }

  @Test
  public void test32000Binds() throws Exception {
    testNBinds(32000);
  }

  @Test
  public void test17000Binds() throws Exception {
    testNBinds(17000);
  }

  public void testNBinds(int nBinds) throws Exception {
    PreparedStatement pstmt = null;
    try {
      StringBuilder sb = new StringBuilder();
      sb.append("INSERT INTO testbatch(pk) VALUES (coalesce(?");
      for (int i = 0; i < nBinds - 1 /* note one ? above */; i++) {
        sb.append(",?");
      }
      sb.append("))");
      pstmt = con.prepareStatement(sb.toString());
      for (int k = 0; k < 2; k++) {
        for (int i = 1; i <= nBinds; i++) {
          pstmt.setInt(i, i + k * nBinds);
        }
        pstmt.addBatch();
      }
      Assert.assertEquals(
          "Statement with " + nBinds
              + " binds should not be batched => two executions with exactly one row inserted each",
          Arrays.toString(new int[] { 1, 1 }),
          Arrays.toString(pstmt.executeBatch()));
    } catch (BatchUpdateException be) {
      SQLException e = be;
      while (true) {
        e.printStackTrace();
        SQLException next = e.getNextException();
        if (next == null) {
          break;
        }
        e = next;
      }
      throw e;
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }
}
