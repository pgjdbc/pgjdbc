/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.v3.BatchedQuery;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.jdbc2.BatchExecuteTest;

import org.junit.Test;

import java.lang.reflect.Method;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Properties;

/**
 * This object tests the internals of the BatchedStatementDecorator during
 * execution. Rather than rely on testing at the jdbc api layer.
 * on.
 */
public class DeepBatchedInsertStatementTest extends BaseTest4 {

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
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER");
    TestUtil.createTable(con, "testunspecified", "pk INTEGER, bday TIMESTAMP");

    stmt.executeUpdate("INSERT INTO testbatch VALUES (1, 0)");
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
    TestUtil.dropTable(con, "testbatch");
    TestUtil.dropTable(con, "testunspecified");
    super.tearDown();
  }

  @Override
  protected void updateProperties(Properties props) {
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, true);
    forceBinary(props);
  }

  @Test
  public void testDeepInternalsBatchedQueryDecorator() throws Exception {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement) con.prepareStatement("INSERT INTO testbatch VALUES (?,?)");

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch(); // initial pass
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();// preparedQuery should be wrapped

      BatchedQuery[] bqds;
      bqds = transformBQD(pstmt);
      assertEquals(2, getBatchSize(bqds));

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();

      bqds = transformBQD(pstmt);
      assertEquals(3, getBatchSize(bqds));

      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());
      bqds = transformBQD(pstmt);

      assertEquals(0, getBatchSize(bqds));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();

      bqds = transformBQD(pstmt);
      assertEquals(1, getBatchSize(bqds));

      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(2, getBatchSize(bqds));

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(3, getBatchSize(bqds));

      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(1, getBatchSize(bqds));
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(2, getBatchSize(bqds));

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(3, getBatchSize(bqds));

      pstmt.setInt(1, 7);
      pstmt.setInt(2, 8);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(4, getBatchSize(bqds));

      BatchExecuteTest.assertSimpleInsertBatch(4, pstmt.executeBatch());

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(1, getBatchSize(bqds));
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(2, getBatchSize(bqds));

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(3, getBatchSize(bqds));

      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(1, getBatchSize(bqds));
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(2, getBatchSize(bqds));

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(3, getBatchSize(bqds));

      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(1, getBatchSize(bqds));
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      bqds = transformBQD(pstmt);
      assertEquals(2, getBatchSize(bqds));

      BatchExecuteTest.assertSimpleInsertBatch(2, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   *
   */
  @Test
  public void testUnspecifiedParameterType() throws Exception {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement) con
          .prepareStatement("INSERT INTO testunspecified VALUES (?,?)");

      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(1));
      pstmt.addBatch();

      pstmt.setInt(1, 2);
      pstmt.setDate(2, new Date(2));
      pstmt.addBatch();

      BatchExecuteTest.assertSimpleInsertBatch(2, pstmt.executeBatch());

      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(3));
      pstmt.addBatch();
      pstmt.setInt(1, 2);
      pstmt.setDate(2, new Date(4));
      pstmt.addBatch();

      BatchExecuteTest.assertSimpleInsertBatch(2, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * Test to check the statement can provide the necessary number of prepared
   * type fields. This is after running with a batch size of 1.
   */
  @Test
  public void testVaryingTypeCounts() throws SQLException {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement)con.prepareStatement("INSERT INTO testunspecified VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(1));
      pstmt.addBatch();

      BatchExecuteTest.assertSimpleInsertBatch(1, pstmt.executeBatch());
      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(2));
      pstmt.addBatch();
      pstmt.setInt(1, 2);
      pstmt.setDate(2, new Date(3));
      pstmt.addBatch();

      pstmt.setInt(1, 3);
      pstmt.setDate(2, new Date(4));
      pstmt.addBatch();
      pstmt.setInt(1, 4);
      pstmt.setDate(2, new Date(5));
      pstmt.addBatch();

      BatchExecuteTest.assertSimpleInsertBatch(4, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * This method triggers the transformation of single batches to multi batches.
   *
   * @param ps PgPreparedStatement statement that will contain the field
   * @return BatchedQueryDecorator[] queries after conversion
   * @throws Exception fault raised when the field cannot be accessed
   */
  private BatchedQuery[] transformBQD(PgPreparedStatement ps) throws Exception {
    // We store collections that get replace on the statement
    ArrayList<Query> batchStatements = ps.batchStatements;
    ArrayList<ParameterList> batchParameters = ps.batchParameters;
    ps.transformQueriesAndParameters();
    BatchedQuery[] bqds = ps.batchStatements.toArray(new BatchedQuery[0]);
    // Restore collections on the statement.
    ps.batchStatements = batchStatements;
    ps.batchParameters = batchParameters;
    return bqds;
  }

  /**
   * Get the total batch size of multi batches.
   *
   * @param bqds the converted queries
   * @return the total batch size
   */
  private int getBatchSize(BatchedQuery[] bqds) {
    int total = 0;
    for (BatchedQuery bqd : bqds) {
      total += bqd.getBatchSize();
    }
    return total;
  }

  /**
   * Access the encoded statement name field.
   * Again using reflection to gain access to a private field member
   * @param bqd BatchedQueryDecorator object on which field is present
   * @return byte[] array of bytes that represent the statement name
   *     when encoded
   * @throws Exception fault raised if access to field not possible
   */
  private byte[] getEncodedStatementName(BatchedQuery bqd)
      throws Exception {
    Class<?> clazz = Class.forName("org.postgresql.core.v3.SimpleQuery");
    Method mESN = clazz.getDeclaredMethod("getEncodedStatementName");
    mESN.setAccessible(true);
    return (byte[]) mESN.invoke(bqd);
  }
}
