/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.BatchUpdateException;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

/**
 * Test case for Statement.batchExecute()
 * TODO tests that can be added to this test case
 *   - SQLExceptions chained to a BatchUpdateException
 *   - test PreparedStatement as thoroughly as Statement
 */
@ParameterizedClass
@MethodSource("data")
public class BatchExecuteTest extends BaseTest4 {

  private boolean insertRewrite;

  public BatchExecuteTest(BinaryMode binaryMode, boolean insertRewrite) {
    this.insertRewrite = insertRewrite;
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      for (boolean insertRewrite : new boolean[]{false, true}) {
        ids.add(new Object[]{binaryMode, insertRewrite});
      }
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.REWRITE_BATCHED_INSERTS.set(props, insertRewrite);
  }

  // Set up the fixture for this testcase: a connection to a database with
  // a table for this test.
  @Override
  public void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();

    // Drop the test table if it already exists for some reason. It is
    // not an error if it doesn't exist.
    TestUtil.createTempTable(con, "testbatch", "pk INTEGER, col1 INTEGER");

    stmt.executeUpdate("INSERT INTO testbatch VALUES (1, 0)");

    TestUtil.createTempTable(con, "prep", "a integer, b integer, d date");

    TestUtil.createTempTable(con, "batchUpdCnt", "id varchar(512) primary key, data varchar(512)");
    stmt.executeUpdate("INSERT INTO batchUpdCnt(id) VALUES ('key-2')");

    stmt.close();

    // Generally recommended with batch updates. By default we run all
    // tests in this test case with autoCommit disabled.
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  @Override
  public void tearDown() throws SQLException {
    con.setAutoCommit(true);

    TestUtil.dropTable(con, "testbatch");
    super.tearDown();
  }

  @Test
  public void testSupportsBatchUpdates() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertTrue(dbmd.supportsBatchUpdates(), "Expected that Batch Updates are supported");
  }

  @Test
  public void testEmptyClearBatch() throws Exception {
    Statement stmt = con.createStatement();
    stmt.clearBatch(); // No-op.

    PreparedStatement ps = con.prepareStatement("SELECT ?");
    ps.clearBatch(); // No-op.
  }

  private void assertCol1HasValue(int expected) throws Exception {
    Statement getCol1 = con.createStatement();
    try {
      ResultSet rs = getCol1.executeQuery("SELECT col1 FROM testbatch WHERE pk = 1");
      assertTrue(rs.next());

      int actual = rs.getInt("col1");

      assertEquals(expected, actual);
      assertFalse(rs.next());

      rs.close();
    } finally {
      TestUtil.closeQuietly(getCol1);
    }
  }

  @Test
  public void testExecuteEmptyBatch() throws Exception {
    Statement stmt = con.createStatement();
    try {
      int[] updateCount = stmt.executeBatch();
      assertEquals(0, updateCount.length);

      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
      stmt.clearBatch();
      updateCount = stmt.executeBatch();
      assertEquals(0, updateCount.length);
      stmt.close();
    } finally {
      TestUtil.closeQuietly(stmt);
    }
  }

  @Test
  public void testExecuteEmptyPreparedBatch() throws Exception {
    PreparedStatement ps = con.prepareStatement("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
    try {
      int[] updateCount = ps.executeBatch();
      assertEquals(0, updateCount.length, "Empty batch should update empty result");
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  public void testPreparedNoParameters() throws SQLException {
    PreparedStatement ps = con.prepareStatement("INSERT INTO prep(a) VALUES (1)");
    try {
      ps.addBatch();
      ps.addBatch();
      ps.addBatch();
      ps.addBatch();
      int[] actual = ps.executeBatch();
      assertBatchResult("4 rows inserted via batch", new int[]{1, 1, 1, 1}, actual);
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  public void testClearBatch() throws Exception {
    Statement stmt = con.createStatement();
    try {
      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
      assertCol1HasValue(0);
      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");
      assertCol1HasValue(0);
      stmt.clearBatch();
      assertCol1HasValue(0);
      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 4 WHERE pk = 1");
      assertCol1HasValue(0);
      stmt.executeBatch();
      assertCol1HasValue(4);
      con.commit();
      assertCol1HasValue(4);
    } finally {
      TestUtil.closeQuietly(stmt);
    }
  }

  @Test
  public void testClearPreparedNoArgBatch() throws Exception {
    PreparedStatement ps = con.prepareStatement("INSERT INTO prep(a) VALUES (1)");
    try {
      ps.addBatch();
      ps.clearBatch();
      int[] updateCount = ps.executeBatch();
      assertEquals(0, updateCount.length, "Empty batch should update empty result");
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  public void testClearPreparedEmptyBatch() throws Exception {
    PreparedStatement ps = con.prepareStatement("INSERT INTO prep(a) VALUES (1)");
    try {
      ps.clearBatch();
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  public void testSelectInBatch() throws Exception {
    Statement stmt = stmt = con.createStatement();
    try {
      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
      stmt.addBatch("SELECT col1 FROM testbatch WHERE pk = 1");
      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");

      // There's no reason to Assert.fail
      int[] updateCounts = stmt.executeBatch();

      assertTrue(updateCounts[0] == 1 || updateCounts[0] == Statement.SUCCESS_NO_INFO, "First update should succeed, thus updateCount should be 1 or SUCCESS_NO_INFO"
              + ", actual value: " + updateCounts[0]);
      assertTrue(updateCounts[1] == 0 || updateCounts[1] == Statement.SUCCESS_NO_INFO, "For SELECT, number of modified rows should be either 0 or SUCCESS_NO_INFO"
              + ", actual value: " + updateCounts[1]);
      assertTrue(updateCounts[2] == 1 || updateCounts[2] == Statement.SUCCESS_NO_INFO, "Second update should succeed, thus updateCount should be 1 or SUCCESS_NO_INFO"
              + ", actual value: " + updateCounts[2]);
    } finally {
      TestUtil.closeQuietly(stmt);
    }
  }

  @Test
  public void testSelectInBatchThrowsAutoCommit() throws Exception {
    con.setAutoCommit(true);
    testSelectInBatchThrows();
  }

  @Test
  public void testSelectInBatchThrows() throws Exception {
    Statement stmt = con.createStatement();
    try {
      int oldValue = getCol1Value();
      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
      stmt.addBatch("SELECT 0/0 FROM testbatch WHERE pk = 1");
      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");

      int[] updateCounts;
      try {
        updateCounts = stmt.executeBatch();
        fail("0/0 should throw BatchUpdateException");
      } catch (BatchUpdateException be) {
        updateCounts = be.getUpdateCounts();
      }

      if (!con.getAutoCommit()) {
        con.commit();
      }

      int newValue = getCol1Value();
      boolean firstOk = updateCounts[0] == 1 || updateCounts[0] == Statement.SUCCESS_NO_INFO;
      boolean lastOk = updateCounts[2] == 1 || updateCounts[2] == Statement.SUCCESS_NO_INFO;

      assertEquals(oldValue + (firstOk ? 1 : 0) + (lastOk ? 2 : 0), newValue, "testbatch.col1 should account +1 and +2 for the relevant successful rows: "
              + Arrays.toString(updateCounts));

      assertEquals(Statement.EXECUTE_FAILED, updateCounts[1], "SELECT 0/0 should be marked as Statement.EXECUTE_FAILED");

    } finally {
      TestUtil.closeQuietly(stmt);
    }
  }

  @Test
  public void testMultiStatementSqlInAddBatch() throws Exception {
    // Multi-statement SQL in a single addBatch() entry has never worked in pgjdbc:
    // BatchResultHandler allocates one update-count slot per batch entry, but
    // CompositeQuery emits one CommandComplete per sub-statement, so it used to fail
    // deep inside the protocol layer with "Too many update results were returned"
    // or ClassCastException. Reject it at the JDBC boundary with a clear message.
    try (Statement stmt = con.createStatement()) {
      String sql = "UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1;"
          + " UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1";
      SQLException sqle = assertThrows(SQLException.class, () -> stmt.addBatch(sql));
      assertEquals(PSQLState.NOT_IMPLEMENTED.getState(), sqle.getSQLState(),
          "Multi-statement addBatch() should fail with NOT_IMPLEMENTED SQLState");
      assertEquals(0, getCol1Value(),
          "Rejected addBatch() must not execute either UPDATE.");
    }
  }

  private int getCol1Value() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      ResultSet rs = stmt.executeQuery("select col1 from testbatch where pk=1");
      rs.next();
      return rs.getInt(1);
    } finally {
      stmt.close();
    }
  }

  @Test
  public void testStringAddBatchOnPreparedStatement() throws Exception {
    PreparedStatement pstmt =
        con.prepareStatement("UPDATE testbatch SET col1 = col1 + ? WHERE PK = ?");
    pstmt.setInt(1, 1);
    pstmt.setInt(2, 1);
    pstmt.addBatch();

    try {
      pstmt.addBatch("UPDATE testbatch SET col1 = 3");
      fail("Should have thrown an exception about using the string addBatch method on a prepared statement.");
    } catch (SQLException sqle) {
    }

    pstmt.close();
  }

  @Test
  public void testPreparedStatement() throws Exception {
    PreparedStatement pstmt =
        con.prepareStatement("UPDATE testbatch SET col1 = col1 + ? WHERE PK = ?");

    // Note that the first parameter changes for every statement in the
    // batch, whereas the second parameter remains constant.
    pstmt.setInt(1, 1);
    pstmt.setInt(2, 1);
    pstmt.addBatch();
    assertCol1HasValue(0);

    pstmt.setInt(1, 2);
    pstmt.addBatch();
    assertCol1HasValue(0);

    pstmt.setInt(1, 4);
    pstmt.addBatch();
    assertCol1HasValue(0);

    pstmt.executeBatch();
    assertCol1HasValue(7);

    // now test to see that we can still use the statement after the execute
    pstmt.setInt(1, 3);
    pstmt.addBatch();
    assertCol1HasValue(7);

    pstmt.executeBatch();
    assertCol1HasValue(10);

    con.commit();
    assertCol1HasValue(10);

    con.rollback();
    assertCol1HasValue(10);

    pstmt.close();
  }

  @Test
  public void testTransactionalBehaviour() throws Exception {
    Statement stmt = con.createStatement();

    stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
    stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");
    stmt.executeBatch();
    con.rollback();
    assertCol1HasValue(0);

    stmt.addBatch("UPDATE testbatch SET col1 = col1 + 4 WHERE pk = 1");
    stmt.addBatch("UPDATE testbatch SET col1 = col1 + 8 WHERE pk = 1");

    // The statement has been added to the batch, but it should not yet
    // have been executed.
    assertCol1HasValue(0);

    int[] updateCounts = stmt.executeBatch();
    assertEquals(2, updateCounts.length);
    assertEquals(1, updateCounts[0]);
    assertEquals(1, updateCounts[1]);

    assertCol1HasValue(12);
    con.commit();
    assertCol1HasValue(12);
    con.rollback();
    assertCol1HasValue(12);

    TestUtil.closeQuietly(stmt);
  }

  @Test
  public void testWarningsAreCleared() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.addBatch("CREATE TEMP TABLE unused (a int primary key)");
    stmt.executeBatch();
    // Execute an empty batch to clear warnings.
    stmt.executeBatch();
    assertNull(stmt.getWarnings());
    TestUtil.closeQuietly(stmt);
  }

  @Test
  public void testBatchEscapeProcessing() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("CREATE TEMP TABLE batchescape (d date)");

    stmt.addBatch("INSERT INTO batchescape (d) VALUES ({d '2007-11-20'})");
    stmt.executeBatch();

    PreparedStatement pstmt =
        con.prepareStatement("INSERT INTO batchescape (d) VALUES ({d '2007-11-20'})");
    pstmt.addBatch();
    pstmt.executeBatch();
    pstmt.close();

    ResultSet rs = stmt.executeQuery("SELECT d FROM batchescape");
    assertTrue(rs.next());
    assertEquals("2007-11-20", rs.getString(1));
    assertTrue(rs.next());
    assertEquals("2007-11-20", rs.getString(1));
    assertTrue(!rs.next());
    TestUtil.closeQuietly(stmt);
  }

  @Test
  public void testBatchWithEmbeddedNulls() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.execute("CREATE TEMP TABLE batchstring (a text)");

    con.commit();

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO batchstring VALUES (?)");

    try {
      pstmt.setString(1, "a");
      pstmt.addBatch();
      pstmt.setString(1, "\u0000");
      pstmt.addBatch();
      pstmt.setString(1, "b");
      pstmt.addBatch();
      pstmt.executeBatch();
      fail("Should have thrown an exception.");
    } catch (SQLException sqle) {
      con.rollback();
    }
    pstmt.close();

    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM batchstring");
    assertTrue(rs.next());
    assertEquals(0, rs.getInt(1));
    TestUtil.closeQuietly(stmt);
  }

  @Test
  public void testMixedBatch() throws SQLException {
    try {
      Statement st = con.createStatement();
      st.executeUpdate("DELETE FROM prep;");
      st.close();

      st = con.createStatement();
      st.addBatch("INSERT INTO prep (a, b) VALUES (1,2)");
      st.addBatch("INSERT INTO prep (a, b) VALUES (100,200)");
      st.addBatch("DELETE FROM prep WHERE a = 1 AND b = 2");
      st.addBatch("CREATE TEMPORARY TABLE waffles(sauce text)");
      st.addBatch("INSERT INTO waffles(sauce) VALUES ('cream'), ('strawberry jam')");
      int[] batchResult = st.executeBatch();
      assertEquals(1, batchResult[0]);
      assertEquals(1, batchResult[1]);
      assertEquals(1, batchResult[2]);
      assertEquals(0, batchResult[3]);
      assertEquals(2, batchResult[4]);
    } catch (SQLException ex) {
      ex.getNextException().printStackTrace();
      throw ex;
    }
  }

  /*
   * A user reported that a query that uses RETURNING (via getGeneratedKeys) in a batch, and a
   * 'text' field value in a table is assigned NULL in the first execution of the batch then
   * non-NULL afterwards using PreparedStatement.setObject(int, Object) (i.e. no Types param or
   * setString call) the batch may Assert.fail with:
   *
   * "Received resultset tuples, but no field structure for them"
   *
   * at org.postgresql.core.v3.QueryExecutorImpl.processResults
   *
   * Prior to 245b388 it would instead Assert.fail with a NullPointerException in
   * AbstractJdbc2ResultSet.checkColumnIndex
   *
   * The cause is complicated. The Assert.failure arises because the query gets re-planned mid-batch. This
   * re-planning clears the cached information about field types. The field type information for
   * parameters gets re-acquired later but the information for *returned* values does not.
   *
   * (The reason why the returned value types aren't recalculated is not yet known.)
   *
   * The re-plan's cause is its self complicated.
   *
   * The first bind of the parameter, which is null, gets the type oid 0 (unknown/unspecified).
   * Unless Types.VARCHAR is specified or setString is used, in which case the oid is set to 1043
   * (varchar).
   *
   * The second bind identifies the object class as String so it calls setString internally. This
   * sets the type to 1043 (varchar).
   *
   * The third and subsequent binds, whether null or non-null, will get type 1043, because there's
   * logic to avoid overwriting a known parameter type with the unknown type oid. This is why the
   * issue can only occur when null is the first entry.
   *
   * When executed the first time a describe is run. This reports the parameter oid to be 25 (text),
   * because that's the type of the table column the param is being assigned to. That's why the cast
   * to ?::varchar works - because it overrides the type for the parameter to 1043 (varchar).
   *
   * The second execution sees that the bind parameter type is already known to PgJDBC as 1043
   * (varchar). PgJDBC doesn't see that text and varchar are the same - and, in fact, under some
   * circumstances they aren't exactly the same. So it discards the planned query and re-plans.
   *
   * This issue can be reproduced with any pair of implicitly or assignment castable types; for
   * example, using Integer in JDBC and bigint in the Pg table will do it.
   */
  @Test
  public void testBatchReturningMixedNulls() throws SQLException {
    String[] testData = new String[]{null, "test", null, null, null};

    try {
      Statement setup = con.createStatement();
      setup.execute("DROP TABLE IF EXISTS mixednulltest;");
      // It's significant that "value' is 'text' not 'varchar' here;
      // if 'varchar' is used then everything works fine.
      setup.execute("CREATE TABLE mixednulltest (key serial primary key, value text);");
      setup.close();

      // If the parameter is given as ?::varchar then this issue
      // does not arise.
      PreparedStatement st =
          con.prepareStatement("INSERT INTO mixednulltest (value) VALUES (?)", new String[]{"key"});

      for (String val : testData) {
        /*
         * This is the crucial bit. It's set to null first time around, so the RETURNING clause's
         * type oid is undefined.
         *
         * The second time around the value is assigned so Pg reports the type oid is TEXT, like the
         * table. But we expected VARCHAR.
         *
         * This causes PgJDBC to replan the query, and breaks other things.
         */
        st.setObject(1, val);
        st.addBatch();
      }
      st.executeBatch();
      ResultSet rs = st.getGeneratedKeys();
      for (int i = 1; i <= testData.length; i++) {
        rs.next();
        assertEquals(i, rs.getInt(1));
      }
      assertTrue(!rs.next());
    } catch (SQLException ex) {
      ex.getNextException().printStackTrace();
      throw ex;
    }
  }

  @Test
  public void testBatchWithAlternatingAndUnknownTypes0() throws SQLException {
    testBatchWithAlternatingAndUnknownTypesN(0);
  }

  @Test
  public void testBatchWithAlternatingAndUnknownTypes1() throws SQLException {
    testBatchWithAlternatingAndUnknownTypesN(1);
  }

  @Test
  public void testBatchWithAlternatingAndUnknownTypes2() throws SQLException {
    testBatchWithAlternatingAndUnknownTypesN(2);
  }

  @Test
  public void testBatchWithAlternatingAndUnknownTypes3() throws SQLException {
    testBatchWithAlternatingAndUnknownTypesN(3);
  }

  @Test
  public void testBatchWithAlternatingAndUnknownTypes4() throws SQLException {
    testBatchWithAlternatingAndUnknownTypesN(4);
  }

  @Test
  public void testBatchWithAlternatingAndUnknownTypes5() throws SQLException {
    testBatchWithAlternatingAndUnknownTypesN(5);
  }

  @Test
  public void testBatchWithAlternatingAndUnknownTypes6() throws SQLException {
    testBatchWithAlternatingAndUnknownTypesN(6);
  }

  /**
   * This one is reproduced in regular (non-force binary) mode.
   *
   * <p>As of 9.4.1208 the following tests fail:
   * BatchExecuteTest.testBatchWithAlternatingAndUnknownTypes3
   * BatchExecuteTest.testBatchWithAlternatingAndUnknownTypes4
   * BatchExecuteTest.testBatchWithAlternatingAndUnknownTypes5
   * BatchExecuteTest.testBatchWithAlternatingAndUnknownTypes6</p>
   * @param numPreliminaryInserts number of preliminary inserts to make so the statement gets
   *                              prepared
   * @throws SQLException in case of failure
   */
  public void testBatchWithAlternatingAndUnknownTypesN(int numPreliminaryInserts)
      throws SQLException {
    PreparedStatement ps = null;
    try {
      con.setAutoCommit(true);
      // This test requires autoCommit false to reproduce
      ps = con.prepareStatement("insert into prep(a, d) values(?, ?)");
      for (int i = 0; i < numPreliminaryInserts; i++) {
        ps.setNull(1, Types.SMALLINT);
        ps.setObject(2, new Date(42));
        ps.addBatch();
        ps.executeBatch();
      }

      ps.setObject(1, 43.0);
      ps.setObject(2, new Date(43));
      ps.addBatch();
      ps.setNull(1, Types.SMALLINT);
      ps.setObject(2, new Date(44));
      ps.addBatch();
      ps.executeBatch();

      ps.setObject(1, 45.0);
      ps.setObject(2, new Date(45)); // <-- this causes "oid of bind unknown, send Describe"
      ps.addBatch();
      ps.setNull(1, Types.SMALLINT);
      ps.setNull(2, Types.DATE);     // <-- this uses Oid.DATE, thus no describe message
      // As the same query object was reused the describe from Date(45) overwrites
      // parameter types, thus Double(45)'s type (double) comes instead of SMALLINT.
      // Thus pgjdbc thinks the prepared statement is prepared for (double, date) types
      // however in reality the statement is prepared for (smallint, date) types.

      ps.addBatch();
      ps.executeBatch();

      // This execution with (double, unknown) passes isPreparedForTypes check, and causes
      // the failure
      ps.setObject(1, 47.0);
      ps.setObject(2, new Date(47));
      ps.addBatch();
      ps.executeBatch();
    } catch (BatchUpdateException e) {
      throw e.getNextException();
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  /**
   * Tests {@link PreparedStatement#addBatch} in case types of parameters change from one batch to
   * another. Change of the datatypes causes re-prepare server-side statement, thus exactly the same
   * query object might have different statement names.
   */
  @Test
  public void testBatchWithAlternatingTypes() throws SQLException {
    try {
      Statement s = con.createStatement();
      s.execute("BEGIN");
      PreparedStatement ps;
      ps = con.prepareStatement("insert into prep(a,b)  values(?::int4,?)");
      ps.setInt(1, 2);
      ps.setInt(2, 2);
      ps.addBatch();
      ps.addBatch();
      ps.addBatch();
      ps.addBatch();
      ps.addBatch();
      ps.setString(1, "1");
      ps.setInt(2, 2);
      ps.addBatch();
      ps.executeBatch();
      ps.setString(1, "2");
      ps.setInt(2, 2);
      ps.addBatch();
      ps.executeBatch();
      ps.close();
      s.execute("COMMIT");
    } catch (BatchUpdateException e) {
      throw e.getNextException();
    }
  }

  @Test
  public void testSmallBatchUpdateFailureSimple() throws SQLException {
    con.setAutoCommit(true);

    // update as batch
    PreparedStatement batchSt = con.prepareStatement("INSERT INTO batchUpdCnt(id) VALUES (?)");
    batchSt.setString(1, "key-1");
    batchSt.addBatch();

    batchSt.setString(1, "key-2");
    batchSt.addBatch();

    int[] batchResult;
    try {
      batchResult = batchSt.executeBatch();
      fail("Expecting BatchUpdateException as key-2 is duplicated in batchUpdCnt.id. "
          + " executeBatch returned " + Arrays.toString(batchResult));
    } catch (BatchUpdateException ex) {
      batchResult = ex.getUpdateCounts();
    } finally {
      TestUtil.closeQuietly(batchSt);
    }

    int newCount = getBatchUpdCount();
    if (newCount == 2) {
      // key-1 did succeed
      assertTrue(batchResult[0] == 1 || batchResult[0] == Statement.SUCCESS_NO_INFO, "batchResult[0] should be 1 or SUCCESS_NO_INFO since 'key-1' was inserted,"
          + " actual result is " + Arrays.toString(batchResult));
    } else {
      assertTrue(batchResult[0] == 0 || batchResult[0] == Statement.EXECUTE_FAILED, "batchResult[0] should be 0 or EXECUTE_FAILED since 'key-1' was NOT inserted,"
              + " actual result is " + Arrays.toString(batchResult));
    }

    assertEquals(Statement.EXECUTE_FAILED, batchResult[1], "'key-2' insertion should have Assert.failed");
  }

  private int getBatchUpdCount() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select count(*) from batchUpdCnt");
    ResultSet rs = ps.executeQuery();
    assertTrue(rs.next(), "count(*) must return 1 row");
    return rs.getInt(1);
  }

  /**
   * Check batching using two individual statements that are both the same type.
   * Test coverage to check default behaviour is not broken.
   * @throws SQLException for issues during test
   */
  @Test
  public void testBatchWithRepeatedInsertStatement() throws SQLException {
    PreparedStatement pstmt = null;
    /* Optimization to re-write insert statements is disabled by default.
     * Do nothing here.
     */
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?)");
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 1);
      pstmt.addBatch(); //statement one
      pstmt.setInt(1, 2);
      pstmt.setInt(2, 2);
      pstmt.addBatch();//statement two
      int[] outcome = pstmt.executeBatch();

      assertNotNull(outcome);
      assertEquals(2, outcome.length);
      int rowsInserted = insertRewrite ? Statement.SUCCESS_NO_INFO : 1;
      assertEquals(rowsInserted, outcome[0]);
      assertEquals(rowsInserted, outcome[1]);
    } catch (SQLException sqle) {
      fail("Failed to execute two statements added to a batch. Reason:" + sqle.getMessage());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
  * Test case to make sure the update counter is correct for the
  * one statement executed. Test coverage to check default behaviour is
  * not broken.
  * @throws SQLException for issues during test
  */
  @Test
  public void testBatchWithMultiInsert() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?),(?,?)");
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 1);
      pstmt.setInt(3, 2);
      pstmt.setInt(4, 2);
      pstmt.addBatch();//statement one
      int[] outcome = pstmt.executeBatch();
      assertNotNull(outcome);
      assertEquals(1, outcome.length);
      assertEquals(2, outcome[0]);
    } catch (SQLException sqle) {
      fail("Failed to execute two statements added to a batch. Reason:" + sqle.getMessage());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  /**
  * Test case to make sure the update counter is correct for the
  * two double-row statements executed. Test coverage to check default behaviour is
  * not broken.
  * @throws SQLException for issues during test
  */
  @Test
  public void testBatchWithTwoMultiInsertStatements() throws SQLException {
    PreparedStatement pstmt = null;
    try {
      pstmt = con.prepareStatement("INSERT INTO testbatch VALUES (?,?),(?,?)");
      pstmt.setInt(1, 1);
      pstmt.setInt(2, 1);
      pstmt.setInt(3, 2);
      pstmt.setInt(4, 2);
      pstmt.addBatch(); //statement one
      pstmt.setInt(1, 3);
      pstmt.setInt(2, 3);
      pstmt.setInt(3, 4);
      pstmt.setInt(4, 4);
      pstmt.addBatch(); //statement two
      int[] outcome = pstmt.executeBatch();
      int rowsInserted = insertRewrite ? Statement.SUCCESS_NO_INFO : 2;
      assertEquals(Arrays.toString(new int[]{rowsInserted, rowsInserted}), Arrays.toString(outcome), "Inserting two multi-valued statements with two rows each. Expecting {2, 2} rows inserted (or SUCCESS_NO_INFO)");
    } catch (SQLException sqle) {
      fail("Failed to execute two statements added to a batch. Reason:" + sqle.getMessage());
    } finally {
      TestUtil.closeQuietly(pstmt);
    }
  }

  public static void assertSimpleInsertBatch(int n, int[] actual) {
    int[] expected = new int[n];
    Arrays.fill(expected, 1);
    assertBatchResult(n + " addBatch, 1 row each", expected, actual);
  }

  public static void assertBatchResult(String message, int[] expected, int[] actual) {
    int[] clone = expected.clone();
    boolean hasChanges = false;
    for (int i = 0; i < actual.length; i++) {
      int a = actual[i];
      if (a == Statement.SUCCESS_NO_INFO && expected[i] >= 0) {
        clone[i] = a;
        hasChanges = true;
      }
    }
    if (hasChanges) {
      message += ", original expectation: " + Arrays.toString(expected);
    }
    assertEquals(Arrays.toString(clone), Arrays.toString(actual), message);
  }

  @Test
  public void testServerPrepareMultipleRows() throws SQLException {
    PreparedStatement ps = null;
    try {
      ps = con.prepareStatement("INSERT INTO prep(a) VALUES (?)");
      // 2 is not enough for insertRewrite=true case since it would get executed as a single multi-insert statement
      for (int i = 0; i < 3; i++) {
        ps.setInt(1, i);
        ps.addBatch();
      }
      int[] actual = ps.executeBatch();
      assertTrue(((PGStatement) ps).isUseServerPrepare(), "More than 1 row is inserted via executeBatch, it should lead to multiple server statements, thus the statements should be server-prepared");
      assertBatchResult("3 rows inserted via batch", new int[]{1, 1, 1}, actual);
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  @Test
  public void testNoServerPrepareOneRow() throws SQLException {
    PreparedStatement ps = null;
    try {
      ps = con.prepareStatement("INSERT INTO prep(a) VALUES (?)");
      ps.setInt(1, 1);
      ps.addBatch();
      int[] actual = ps.executeBatch();
      int prepareThreshold = ((PGStatement) ps).getPrepareThreshold();
      if (prepareThreshold == 1) {
        assertTrue(((PGStatement) ps).isUseServerPrepare(),
            () -> "prepareThreshold=" + prepareThreshold
            + " thus the statement should be server-prepared");
      } else {
        assertFalse(((PGStatement) ps).isUseServerPrepare(),
            () -> "Just one row inserted via executeBatch, prepareThreshold=" + prepareThreshold
            + " thus the statement should not be server-prepared");
      }
      assertBatchResult("1 rows inserted via batch", new int[]{1}, actual);
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }

  /**
   * When the server terminates the connection in the middle of a batch, executeBatch should report a
   * plain SQLException rather than failing with an AssertionError when assertions are enabled.
   * See <a href="https://github.com/pgjdbc/pgjdbc/issues/1458">issue 1458</a>.
   */
  @Test
  public void testBatchOnTerminatedConnection() throws Exception {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4),
        "pg_terminate_backend(...) requires PostgreSQL 8.4+");

    try (PreparedStatement ps = con.prepareStatement("INSERT INTO prep(a) VALUES (?)")) {
      ps.setInt(1, 1);
      ps.addBatch();
      ps.setInt(1, 2);
      ps.addBatch();

      // Kill the backend so that executeBatch operates on a connection that is already gone.
      TestUtil.terminateBackend(con);

      // The driver must surface a SQLException; it must not throw an AssertionError even when the
      // JVM runs with -ea (see BatchResultHandler.isAutoCommit).
      assertThrows(SQLException.class, ps::executeBatch,
          "executeBatch on a terminated connection should throw SQLException");
    } finally {
      // The connection is gone, so re-open a fresh one for tearDown to clean up against.
      TestUtil.closeDB(con);
      con = TestUtil.openDB();
      con.setAutoCommit(false);
    }
  }
}
