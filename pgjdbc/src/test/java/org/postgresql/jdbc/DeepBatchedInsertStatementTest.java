/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.jdbc;

import org.postgresql.PGProperty;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.v3.BatchedQueryDecorator;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

/**
 * This object tests the internals of the BatchedStatementDecorator during
 * execution. Rather than rely on testing at the jdbc api layer.
 * on.
 */
public class DeepBatchedInsertStatementTest extends BaseTest {

  public void testDeepInternalsBatchedQueryDecorator() throws Exception {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement) con
          .prepareStatement("INSERT INTO testbatch VALUES (?,?)");

      int initParamCount = 2;
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch(); // initial pass
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();// preparedQuery should be wrapped

      BatchedQueryDecorator bqd = getBatchedQueryDecorator(pstmt);
      assertEquals(2, bqd.getBatchSize());

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();

      assertEquals(3, bqd.getBatchSize());
      assertTrue(
          "Expected type information for each parameter not found.",
          Arrays.equals(new int[] { Oid.INT4, Oid.INT4, Oid.INT4, Oid.INT4,
              Oid.INT4, Oid.INT4 }, bqd.getStatementTypes()));

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));

      /* The statement will have been reset. */
      int resetParamCount = bqd.getBindPositions();

      assertEquals(1, bqd.getBatchSize());
      assertEquals(2, bqd.getStatementTypes().length);
      assertEquals(initParamCount, bqd.getStatementTypes().length);
      assertEquals(initParamCount, resetParamCount);

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();

      assertEquals(1, bqd.getBatchSize());

      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(2, bqd.getBatchSize());
      assertEquals(4, bqd.getStatementTypes().length);

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      assertEquals(3, bqd.getBatchSize());
      assertEquals(6, bqd.getStatementTypes().length);

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));
      assertTrue("Expected encoded name is not matched.", Arrays.equals( "S_2".getBytes(),
          getEncodedStatementName(bqd)));

      assertEquals(2, bqd.getStatementTypes().length);

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      assertEquals(1, bqd.getBatchSize());
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(2, bqd.getBatchSize());
      assertEquals(4, bqd.getStatementTypes().length);

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      assertEquals(3, bqd.getBatchSize());
      assertEquals(6, bqd.getStatementTypes().length);

      pstmt.setInt(1, 7);
      pstmt.setInt(2, 8);
      pstmt.addBatch();
      assertEquals(4, bqd.getBatchSize());
      assertEquals(8, bqd.getStatementTypes().length);

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }, pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      assertEquals(1, bqd.getBatchSize());
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(2, bqd.getBatchSize());
      assertEquals(4, bqd.getStatementTypes().length);

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      assertEquals(3, bqd.getBatchSize());
      assertEquals(6, bqd.getStatementTypes().length);

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      assertEquals(1, bqd.getBatchSize());
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(2, bqd.getBatchSize());
      assertEquals(4, bqd.getStatementTypes().length);

      pstmt.setInt(1, 5);
      pstmt.setInt(2, 6);
      pstmt.addBatch();
      assertEquals(3, bqd.getBatchSize());
      assertEquals(6, bqd.getStatementTypes().length);

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO },
              pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      assertEquals(1, bqd.getBatchSize());
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch();
      assertEquals(2, bqd.getBatchSize());
      assertEquals(4, bqd.getStatementTypes().length);

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }, pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  /**
   *
   */
  public void testUnspecifiedParameterType() throws Exception {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement) con
          .prepareStatement("INSERT INTO testunspecified VALUES (?,?)");

      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(1970, 01, 01));
      pstmt.addBatch();

      pstmt.setInt(1, 2);
      pstmt.setDate(2, new Date(1971, 01, 01));
      pstmt.addBatch();

      BatchedQueryDecorator bqd = getBatchedQueryDecorator(pstmt);
      assertTrue(
          "Expected type information for each parameter not found.",
          Arrays.equals(new int[] { Oid.INT4, Oid.UNSPECIFIED, Oid.INT4,
              Oid.UNSPECIFIED }, bqd.getStatementTypes()));

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }, pstmt.executeBatch()));

      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(1970, 01, 01));
      pstmt.addBatch();
      pstmt.setInt(1, 2);
      pstmt.setDate(2, new Date(1971, 01, 01));
      pstmt.addBatch();

      assertFalse(
          "Expected type information for each Date parameter was not updated by backend.",
          Arrays.equals(new int[] { Oid.INT4, Oid.UNSPECIFIED, Oid.INT4,
              Oid.UNSPECIFIED }, bqd.getStatementTypes()));
      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO }, pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  /**
   * Test to check the statement can provide the necessary number of prepared
   * type fields. This is after running with a batch size of 1.
   */
  public void testVaryingTypeCounts() throws SQLException {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement)con.prepareStatement("INSERT INTO testunspecified VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(1970, 01, 01));
      pstmt.addBatch();

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { 1 }, pstmt.executeBatch()));
      pstmt.setInt(1, 1);
      pstmt.setDate(2, new Date(1970, 01, 01));
      pstmt.addBatch();
      pstmt.setInt(1, 2);
      pstmt.setDate(2, new Date(1971, 01, 01));
      pstmt.addBatch();

      pstmt.setInt(1, 3);
      pstmt.setDate(2, new Date(1972, 01, 01));
      pstmt.addBatch();
      pstmt.setInt(1, 4);
      pstmt.setDate(2, new Date(1973, 01, 01));
      pstmt.addBatch();

      assertTrue(
          "Expected outcome not returned by batch execution.",
          Arrays.equals(new int[] { Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO,
              Statement.SUCCESS_NO_INFO}, pstmt.executeBatch()));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  /**
   * Test the estimation of sql string length.
   * @throws SQLException fault raised due to sql
   */
  public void testNativeSqlSizeCalculation() throws Exception {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement) con.prepareStatement("INSERT INTO testbatch VALUES (?,?)");

      pstmt.setInt(1, 1);
      pstmt.setInt(2, 2);
      pstmt.addBatch();
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 4);
      pstmt.addBatch(); // query will now be decorated
      BatchedQueryDecorator bqd = getBatchedQueryDecorator(pstmt);
      // do size comparison using a sql string built manually versus estimation
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(36, 2, 1) , getLength(bqd, 36, 2, 1));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(36, 2, 3) , getLength(bqd, 36, 2, 3));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(36, 2, 7) , getLength(bqd, 36, 2, 7));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(65, 9, 1) , getLength(bqd, 65, 9, 1));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(10, 10, 1) , getLength(bqd, 10, 10, 1));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(10, 19, 1) , getLength(bqd, 10, 19, 1));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(10, 2, 1) , getLength(bqd, 10, 2, 1));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(10, 2, 18) , getLength(bqd, 10, 2, 18));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(10, 2, 999) , getLength(bqd, 10, 2, 999));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(10, 10, 9998) , getLength(bqd, 10, 10, 9998));
      assertEquals("Expected sql length does not match estimated.", getSqlStringLength(10, 3, 99) , getLength(bqd, 10, 3, 99));
    } finally {
      if (null != pstmt) {
        pstmt.close();
      }
      con.rollback();
    }
  }

  /**
   * This method helps to gain access to BQD field.
   * The reason for using reflection is to preserve encapsulation in the codebase.
   * @param ps PgPreparedStatement statement that will contain the field
   * @return BatchedQueryDecorator field that is needed
   * @throws Exception fault raised when the field cannot be accessed
   */
  private BatchedQueryDecorator getBatchedQueryDecorator(PgPreparedStatement ps)
      throws Exception {
    Field f = ps.getClass().getDeclaredField("preparedQuery");
    assertNotNull(f);
    f.setAccessible(true);
    Object cq = f.get(ps);
    assertNotNull(cq);
    assertTrue(cq instanceof CachedQuery);
    Field fQuery = CachedQuery.class.getDeclaredField("query");
    fQuery.setAccessible(true);
    Object bqd = fQuery.get(cq);
    return (BatchedQueryDecorator) bqd;
  }

  /**
   * Access the encoded statement name field.
   * Again using reflection to gain access to a private field member
   * @param bqd BatchedQueryDecorator object on which field is present
   * @return byte[] array of bytes that represent the statement name
   * when encoded
   * @throws Exception fault raised if access to field not possible
   */
  private byte[] getEncodedStatementName(BatchedQueryDecorator bqd)
      throws Exception {
    Field fEBSN = bqd.getClass().getDeclaredField("batchedEncodedName");
    fEBSN.setAccessible(true);
    return (byte[]) fEBSN.get(bqd);
  }

  /**
   * Create the sql string and return the length of the string
   * @param nativeSize int length of sql statement
   * @param paramCount int number of parameters in single batch
   * @param remainingBatches int remaining number of batches to calculate
   * @return int size of the string
   */
  private int getSqlStringLength(int nativeSize, int paramCount, int remainingBatches) {
    StringBuilder sql = new StringBuilder();
    int param = paramCount;
    for (int i = 0 ; i < nativeSize; i++ ) {
      sql.append("*");
    }
    for (int c = 0; c < remainingBatches; c++) {
      sql.append(",($");
      sql.append(Integer.toString(++param));
      for (int p = 1; p < paramCount; p++) {
        sql.append(",$");
        sql.append(Integer.toString(++param));
      }
      sql.append(")");
    }
    return sql.length();
  }

  /**
   * Get the calculated size of string using the BQD implementation.
   * Use reflection to access the method having private access
   * @param bqd BatchedQueryDecorator decorator object
   * @param nativeSize int length of sql statement string
   * @param paramCount int number of parameters in a single batch
   * @param batchsize int remaining number of batches to calculate
   * @return int size estimated by BQD of the sql string
   */
  private int getLength(BatchedQueryDecorator bqd, int nativeSize, int paramCount,
      int batchSize) throws Exception {
    Method mgetSize = bqd.getClass().getDeclaredMethod("calculateLength",
        Integer.TYPE, Integer.TYPE, Integer.TYPE);
    mgetSize.setAccessible(true);
    return (Integer) mgetSize.invoke(bqd, nativeSize, paramCount, batchSize);
  }

  public DeepBatchedInsertStatementTest(String name) {
    super(name);
    try {
      Class.forName("org.postgresql.Driver");
    } catch (Exception ex) {
    }
  }

  /*
   * Set up the fixture for this testcase: a connection to a database with a
   * table for this test.
   */
  protected void setUp() throws Exception {
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
  protected void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testbatch");
    TestUtil.dropTable(con, "testunspecified");
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
