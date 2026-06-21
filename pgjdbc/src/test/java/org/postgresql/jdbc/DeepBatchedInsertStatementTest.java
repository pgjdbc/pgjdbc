/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.v3.BatchedQuery;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.tags.Slow;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.jdbc2.BatchExecuteTest;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER");
      TestUtil.createTable(con, "testunspecified", "pk INTEGER, bday TIMESTAMP");
      TestUtil.createTable(con, "testsinglecol", "v INTEGER");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "testbatch");
      TestUtil.dropTable(con, "testunspecified");
      TestUtil.dropTable(con, "testsinglecol");
    }
  }

  /*
   * Set up the fixture for this testcase: a connection to a database with a
   * table for this test.
   */
  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "TRUNCATE testbatch");
    TestUtil.execute(con, "TRUNCATE testunspecified");
    TestUtil.execute(con, "TRUNCATE testsinglecol");
    TestUtil.execute(con, "INSERT INTO testbatch VALUES (1, 0)");
    /*
     * Generally recommended with batch updates. By default we run all tests in
     * this test case with autoCommit disabled.
     */
    con.setAutoCommit(false);
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
      pstmt = (PgPreparedStatement) con.prepareStatement("INSERT INTO testunspecified VALUES (?,?)");
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
   * With few columns per row the protocol allows far more than the historical cap of 128 rows, so
   * a single multi-values INSERT absorbs the whole batch. testbatch has two columns, so 256 rows
   * fit in one statement (256 * 2 = 512 binds, well under the protocol limit). The legacy cap would
   * have split this into two statements of 128 rows.
   */
  @Test
  public void testBatchBeyondLegacyCap() throws Exception {
    PgPreparedStatement pstmt = null;
    try {
      pstmt = (PgPreparedStatement) con.prepareStatement("INSERT INTO testbatch VALUES (?,?)");
      final int rows = 256;
      for (int i = 0; i < rows; i++) {
        pstmt.setInt(1, i + 10);
        pstmt.setInt(2, i);
        pstmt.addBatch();
      }

      BatchedQuery[] bqds = transformBQD(pstmt);
      assertEquals(rows, getBatchSize(bqds));
      assertEquals(1, bqds.length, "256 two-column rows fit in a single multi-values INSERT");
      assertEquals(rows, bqds[0].getBatchSize());

      BatchExecuteTest.assertSimpleInsertBatch(rows, pstmt.executeBatch());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
   * reWriteBatchedInsertsSize caps the rows merged into a single statement even when the protocol
   * would allow more.
   */
  @Test
  public void testReWriteBatchedInsertsSizeCap() throws Exception {
    Properties props = new Properties();
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, true);
    PGProperty.REWRITE_BATCHED_INSERTS_SIZE.set(props, 64);
    forceBinary(props);
    try (Connection con2 = TestUtil.openDB(props)) {
      con2.setAutoCommit(false);
      PgPreparedStatement pstmt =
          (PgPreparedStatement) con2.prepareStatement("INSERT INTO testbatch VALUES (?,?)");
      try {
        final int rows = 200;
        for (int i = 0; i < rows; i++) {
          pstmt.setInt(1, i + 1000);
          pstmt.setInt(2, i);
          pstmt.addBatch();
        }

        BatchedQuery[] bqds = transformBQD(pstmt);
        assertEquals(rows, getBatchSize(bqds));
        for (BatchedQuery bqd : bqds) {
          assertTrue(bqd.getBatchSize() <= 64,
              "no statement may exceed the configured cap of 64 rows");
        }

        BatchExecuteTest.assertSimpleInsertBatch(rows, pstmt.executeBatch());
      } finally {
        TestUtil.closeQuietly(pstmt);
      }
    }
  }

  /**
   * Batching around the largest supported block (2^15 rows for a one-column row) must split the
   * rewrite at that boundary and still insert every row exactly once. Tagged {@link Slow} because
   * each case inserts tens of thousands of rows.
   */
  @ParameterizedTest
  @ValueSource(ints = {32767, 32768, 32769})
  @Slow
  public void testLargeSingleColumnBatchAroundMaxBlockSize(int rows) throws SQLException {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO testsinglecol VALUES (?)")) {
      for (int i = 0; i < rows; i++) {
        pstmt.setInt(1, i);
        pstmt.addBatch();
      }
      BatchExecuteTest.assertSimpleInsertBatch(rows, pstmt.executeBatch());
    }
    con.commit();

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT count(*), min(v), max(v) FROM testsinglecol")) {
      assertTrue(rs.next());
      assertEquals(rows, rs.getInt(1), "every batched row should be inserted");
      assertEquals(0, rs.getInt(2), "smallest inserted value");
      assertEquals(rows - 1, rs.getInt(3), "largest inserted value");
    }
  }

  /**
   * This method triggers the transformation of single batches to multi batches.
   *
   * @param ps PgPreparedStatement statement that will contain the field
   * @return BatchedQueryDecorator[] queries after conversion
   * @throws Exception fault raised when the field cannot be accessed
   */
  private static BatchedQuery[] transformBQD(PgPreparedStatement ps) throws Exception {
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
  private static int getBatchSize(BatchedQuery[] bqds) {
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
  private static byte[] getEncodedStatementName(BatchedQuery bqd)
      throws Exception {
    // getEncodedStatementName is declared in the package-private SimpleQuery, BatchedQuery's
    // superclass, so it cannot be named as SimpleQuery.class from this package.
    Class<?> clazz = BatchedQuery.class.getSuperclass();
    Method mESN = clazz.getDeclaredMethod("getEncodedStatementName");
    mESN.setAccessible(true);
    return (byte[]) mESN.invoke(bqd);
  }
}
