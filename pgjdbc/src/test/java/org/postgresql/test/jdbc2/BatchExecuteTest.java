/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;
import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

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

/*
 * TODO tests that can be added to this test case - SQLExceptions chained to a BatchUpdateException
 * - test PreparedStatement as thoroughly as Statement
 */

/*
 * Test case for Statement.batchExecute()
 */
@RunWith(Parameterized.class)
public class BatchExecuteTest extends BaseTest4 {

  private boolean insertRewrite;

  public BatchExecuteTest(BinaryMode binaryMode, boolean insertRewrite) {
    this.insertRewrite = insertRewrite;
    setBinaryMode(binaryMode);
  }

  @Parameterized.Parameters(name = "binary = {0}, insertRewrite = {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
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
    Assert.assertTrue("Expected that Batch Updates are supported", dbmd.supportsBatchUpdates());
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
      Assert.assertTrue(rs.next());

      int actual = rs.getInt("col1");

      Assert.assertEquals(expected, actual);
      Assert.assertFalse(rs.next());

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
      Assert.assertEquals(0, updateCount.length);

      stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
      stmt.clearBatch();
      updateCount = stmt.executeBatch();
      Assert.assertEquals(0, updateCount.length);
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
      Assert.assertEquals("Empty batch should update empty result", 0, updateCount.length);
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
      Assert.assertEquals("Empty batch should update empty result", 0, updateCount.length);
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

      Assert.assertTrue("First update should succeed, thus updateCount should be 1 or SUCCESS_NO_INFO"
              + ", actual value: " + updateCounts[0],
          updateCounts[0] == 1 || updateCounts[0] == Statement.SUCCESS_NO_INFO);
      Assert.assertTrue("For SELECT, number of modified rows should be either 0 or SUCCESS_NO_INFO"
              + ", actual value: " + updateCounts[1],
          updateCounts[1] == 0 || updateCounts[1] == Statement.SUCCESS_NO_INFO);
      Assert.assertTrue("Second update should succeed, thus updateCount should be 1 or SUCCESS_NO_INFO"
              + ", actual value: " + updateCounts[2],
          updateCounts[2] == 1 || updateCounts[2] == Statement.SUCCESS_NO_INFO);
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
        Assert.fail("0/0 should throw BatchUpdateException");
      } catch (BatchUpdateException be) {
        updateCounts = be.getUpdateCounts();
      }

      if (!con.getAutoCommit()) {
        con.commit();
      }

      int newValue = getCol1Value();
      boolean firstOk = updateCounts[0] == 1 || updateCounts[0] == Statement.SUCCESS_NO_INFO;
      boolean lastOk = updateCounts[2] == 1 || updateCounts[2] == Statement.SUCCESS_NO_INFO;

      Assert.assertEquals("testbatch.col1 should account +1 and +2 for the relevant successful rows: "
              + Arrays.toString(updateCounts),
          oldValue + (firstOk ? 1 : 0) + (lastOk ? 2 : 0), newValue);

      Assert.assertEquals("SELECT 0/0 should be marked as Statement.EXECUTE_FAILED",
          Statement.EXECUTE_FAILED,
          updateCounts[1]);

    } finally {
      TestUtil.closeQuietly(stmt);
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
      Assert.fail(
          "Should have thrown an exception about using the string addBatch method on a prepared statement.");
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
    Assert.assertEquals(2, updateCounts.length);
    Assert.assertEquals(1, updateCounts[0]);
    Assert.assertEquals(1, updateCounts[1]);

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
    Assert.assertNull(stmt.getWarnings());
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
    Assert.assertTrue(rs.next());
    Assert.assertEquals("2007-11-20", rs.getString(1));
    Assert.assertTrue(rs.next());
    Assert.assertEquals("2007-11-20", rs.getString(1));
    Assert.assertTrue(!rs.next());
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
      Assert.fail("Should have thrown an exception.");
    } catch (SQLException sqle) {
      con.rollback();
    }
    pstmt.close();

    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM batchstring");
    Assert.assertTrue(rs.next());
    Assert.assertEquals(0, rs.getInt(1));
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
      Assert.assertEquals(1, batchResult[0]);
      Assert.assertEquals(1, batchResult[1]);
      Assert.assertEquals(1, batchResult[2]);
      Assert.assertEquals(0, batchResult[3]);
      Assert.assertEquals(2, batchResult[4]);
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
   * The third and subsequent binds, whether null or non-null, will get type 1043, becaues there's
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
        Assert.assertEquals(i, rs.getInt(1));
      }
      Assert.assertTrue(!rs.next());
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
   * <p>This one is reproduced in regular (non-force binary) mode.</p>
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

      ps.setObject(1, new Double(43));
      ps.setObject(2, new Date(43));
      ps.addBatch();
      ps.setNull(1, Types.SMALLINT);
      ps.setObject(2, new Date(44));
      ps.addBatch();
      ps.executeBatch();

      ps.setObject(1, new Double(45));
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
      ps.setObject(1, new Double(47));
      ps.setObject(2, new Date(47));
      ps.addBatch();
      ps.executeBatch();
    } catch (BatchUpdateException e) {
      throw e.getNextException();
    } finally {
      TestUtil.closeQuietly(ps);
    }
    /*
Here's the log
11:33:10.708 (1)  FE=> Parse(stmt=null,query="CREATE TABLE prep (a integer, b integer, d date) ",oids={})
11:33:10.708 (1)  FE=> Bind(stmt=null,portal=null)
11:33:10.708 (1)  FE=> Describe(portal=null)
11:33:10.708 (1)  FE=> Execute(portal=null,limit=1)
11:33:10.708 (1)  FE=> Sync
11:33:10.710 (1)  <=BE ParseComplete [null]
11:33:10.711 (1)  <=BE BindComplete [unnamed]
11:33:10.711 (1)  <=BE NoData
11:33:10.711 (1)  <=BE CommandStatus(CREATE TABLE)
11:33:10.711 (1)  <=BE ReadyForQuery(I)
11:33:10.716 (1) batch execute 1 queries, handler=org.postgresql.jdbc.PgStatement$BatchResultHandler@4629104a, maxRows=0, fetchSize=0, flags=5
11:33:10.716 (1)  FE=> Parse(stmt=null,query="BEGIN",oids={})
11:33:10.717 (1)  FE=> Bind(stmt=null,portal=null)
11:33:10.717 (1)  FE=> Execute(portal=null,limit=0)
11:33:10.718 (1)  FE=> Parse(stmt=null,query="insert into prep(a, d) values($1, $2)",oids={21,0})
11:33:10.718 (1)  FE=> Bind(stmt=null,portal=null,$1=<NULL>:B:21,$2=<'1970-1-1 +3:0:0'>:T:0)
11:33:10.719 (1)  FE=> Describe(portal=null)
11:33:10.719 (1)  FE=> Execute(portal=null,limit=1)
11:33:10.719 (1)  FE=> Sync
11:33:10.720 (1)  <=BE ParseComplete [null]
11:33:10.720 (1)  <=BE BindComplete [unnamed]
11:33:10.720 (1)  <=BE CommandStatus(BEGIN)
11:33:10.720 (1)  <=BE ParseComplete [null]
11:33:10.720 (1)  <=BE BindComplete [unnamed]
11:33:10.720 (1)  <=BE NoData
11:33:10.720 (1)  <=BE CommandStatus(INSERT 0 1)
11:33:10.720 (1)  <=BE ReadyForQuery(T)
11:33:10.721 (1) batch execute 2 queries, handler=org.postgresql.jdbc.PgStatement$BatchResultHandler@27f8302d, maxRows=0, fetchSize=0, flags=5
11:33:10.721 (1)  FE=> Parse(stmt=null,query="insert into prep(a, d) values($1, $2)",oids={701,0})
11:33:10.723 (1)  FE=> Bind(stmt=null,portal=null,$1=<43.0>:B:701,$2=<'1970-1-1 +3:0:0'>:T:0)
11:33:10.723 (1)  FE=> Describe(portal=null)
11:33:10.723 (1)  FE=> Execute(portal=null,limit=1)
11:33:10.723 (1)  FE=> Parse(stmt=null,query="insert into prep(a, d) values($1, $2)",oids={21,0})
11:33:10.723 (1)  FE=> Bind(stmt=null,portal=null,$1=<NULL>:B:21,$2=<'1970-1-1 +3:0:0'>:T:0)
11:33:10.723 (1)  FE=> Describe(portal=null)
11:33:10.723 (1)  FE=> Execute(portal=null,limit=1)
11:33:10.723 (1)  FE=> Sync
11:33:10.723 (1)  <=BE ParseComplete [null]
11:33:10.723 (1)  <=BE BindComplete [unnamed]
11:33:10.725 (1)  <=BE NoData
11:33:10.726 (1)  <=BE CommandStatus(INSERT 0 1)
11:33:10.726 (1)  <=BE ParseComplete [null]
11:33:10.726 (1)  <=BE BindComplete [unnamed]
11:33:10.726 (1)  <=BE NoData
11:33:10.726 (1)  <=BE CommandStatus(INSERT 0 1)
11:33:10.726 (1)  <=BE ReadyForQuery(T)
11:33:10.726 (1) batch execute 2 queries, handler=org.postgresql.jdbc.PgStatement$BatchResultHandler@4d76f3f8, maxRows=0, fetchSize=0, flags=516
11:33:10.726 (1)  FE=> Parse(stmt=S_1,query="insert into prep(a, d) values($1, $2)",oids={701,0})
11:33:10.727 (1)  FE=> Describe(statement=S_1)
11:33:10.728 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<45.0>:B:701,$2=<'1970-1-1 +3:0:0'>:T:0)
11:33:10.728 (1)  FE=> Execute(portal=null,limit=1)
11:33:10.729 (1)  FE=> CloseStatement(S_1)
11:33:10.729 (1)  FE=> Parse(stmt=S_2,query="insert into prep(a, d) values($1, $2)",oids={21,1082})
11:33:10.729 (1)  FE=> Bind(stmt=S_2,portal=null,$1=<NULL>:B:21,$2=<NULL>:B:1082)
11:33:10.729 (1)  FE=> Describe(portal=null)
11:33:10.729 (1)  FE=> Execute(portal=null,limit=1)
11:33:10.729 (1)  FE=> Sync
11:33:10.730 (1)  <=BE ParseComplete [S_2]
11:33:10.730 (1)  <=BE ParameterDescription
11:33:10.730 (1)  <=BE NoData
11:33:10.730 (1)  <=BE BindComplete [unnamed]
11:33:10.730 (1)  <=BE CommandStatus(INSERT 0 1)
11:33:10.730 (1)  <=BE CloseComplete
11:33:10.730 (1)  <=BE ParseComplete [S_2]
11:33:10.730 (1)  <=BE BindComplete [unnamed]
11:33:10.730 (1)  <=BE NoData
11:33:10.731 (1)  <=BE CommandStatus(INSERT 0 1)
11:33:10.731 (1)  <=BE ReadyForQuery(T)
11:33:10.731 (1) batch execute 1 queries, handler=org.postgresql.jdbc.PgStatement$BatchResultHandler@4534b60d, maxRows=0, fetchSize=0, flags=516
11:33:10.731 (1)  FE=> Bind(stmt=S_2,portal=null,$1=<47.0>:B:701,$2=<'1970-1-1 +3:0:0'>:T:1082)
11:33:10.731 (1)  FE=> Describe(portal=null)
11:33:10.731 (1)  FE=> Execute(portal=null,limit=1)
11:33:10.731 (1)  FE=> Sync
11:33:10.732 (1)  <=BE ErrorMessage(ERROR: incorrect binary data format in bind parameter 1)
org.postgresql.util.PSQLException: ERROR: incorrect binary data format in bind parameter 1
  at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2185)
  at org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:1914)
  at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:338)
  at org.postgresql.jdbc.PgStatement.executeBatch(PgStatement.java:2534)
  at org.postgresql.test.jdbc2.BatchExecuteTest.testBatchWithAlternatingTypes2(BatchExecuteTest.java:460)
    */
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
    /*
Key part is (see "before the fix"):
     23:00:30.354 (1)  <=BE ParseComplete [S_2]
     23:00:30.356 (1)  <=BE ParseComplete [S_2]
The problem is ParseRequest is reusing the same Query object and it updates StatementName in place.
This dodges ParseComplete message as previously QueryExecutor just picked statementName from Query object.
Eventually this causes closing of "new" statement instead of old S_1
    23:00:30.356 (1)  FE=> CloseStatement(S_2)

The fix is to make ParseComplete a no-op, so as soon as the driver allocates a statement name, it registers
 the name for cleanup.

Trace before the fix:
23:00:30.261 (1) PostgreSQL 9.4 JDBC4.1 (build 1206)
23:00:30.266 (1) Trying to establish a protocol version 3 connection to localhost:5432
23:00:30.280 (1) Receive Buffer Size is 408300
23:00:30.281 (1) Send Buffer Size is 146988
23:00:30.283 (1)  FE=> StartupPacket(user=postgres, database=vladimirsitnikov, client_encoding=UTF8, DateStyle=ISO, TimeZone=Europe/Volgograd, extra_float_digits=2)
23:00:30.289 (1)  <=BE AuthenticationOk
23:00:30.300 (1)  <=BE ParameterStatus(application_name = )
23:00:30.300 (1)  <=BE ParameterStatus(client_encoding = UTF8)
23:00:30.300 (1)  <=BE ParameterStatus(DateStyle = ISO, DMY)
23:00:30.300 (1)  <=BE ParameterStatus(integer_datetimes = on)
23:00:30.300 (1)  <=BE ParameterStatus(IntervalStyle = postgres)
23:00:30.301 (1)  <=BE ParameterStatus(is_superuser = on)
23:00:30.301 (1)  <=BE ParameterStatus(server_encoding = SQL_ASCII)
23:00:30.301 (1)  <=BE ParameterStatus(server_version = 9.4.5)
23:00:30.301 (1)  <=BE ParameterStatus(session_authorization = postgres)
23:00:30.301 (1)  <=BE ParameterStatus(standard_conforming_strings = on)
23:00:30.301 (1)  <=BE ParameterStatus(TimeZone = Europe/Volgograd)
23:00:30.301 (1)  <=BE BackendKeyData(pid=81221,ckey=2048823749)
23:00:30.301 (1)  <=BE ReadyForQuery(I)
23:00:30.304 (1) simple execute, handler=org.postgresql.core.SetupQueryRunner$SimpleResultHandler@531d72ca, maxRows=0, fetchSize=0, flags=23
23:00:30.304 (1)  FE=> Parse(stmt=null,query="SET extra_float_digits = 3",oids={})
23:00:30.304 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.305 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.305 (1)  FE=> Sync
23:00:30.306 (1)  <=BE ParseComplete [null]
23:00:30.306 (1)  <=BE BindComplete [unnamed]
23:00:30.306 (1)  <=BE CommandStatus(SET)
23:00:30.306 (1)  <=BE ReadyForQuery(I)
23:00:30.306 (1)     compatible = 90400
23:00:30.306 (1)     loglevel = 10
23:00:30.307 (1)     prepare threshold = 5
23:00:30.309 (1)     types using binary send = TIMESTAMPTZ,UUID,INT2_ARRAY,INT4_ARRAY,BYTEA,TEXT_ARRAY,TIMETZ,INT8,INT2,INT4,VARCHAR_ARRAY,INT8_ARRAY,POINT,TIMESTAMP,TIME,BOX,FLOAT4,FLOAT8,FLOAT4_ARRAY,FLOAT8_ARRAY
23:00:30.310 (1)     types using binary receive = TIMESTAMPTZ,UUID,INT2_ARRAY,INT4_ARRAY,BYTEA,TEXT_ARRAY,TIMETZ,INT8,INT2,INT4,VARCHAR_ARRAY,INT8_ARRAY,POINT,DATE,TIMESTAMP,TIME,BOX,FLOAT4,FLOAT8,FLOAT4_ARRAY,FLOAT8_ARRAY
23:00:30.310 (1)     integer date/time = true
23:00:30.331 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@255316f2, maxRows=0, fetchSize=0, flags=21
23:00:30.331 (1)  FE=> Parse(stmt=null,query="DROP TABLE testbatch CASCADE ",oids={})
23:00:30.331 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.331 (1)  FE=> Describe(portal=null)
23:00:30.331 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.331 (1)  FE=> Sync
23:00:30.332 (1)  <=BE ParseComplete [null]
23:00:30.332 (1)  <=BE BindComplete [unnamed]
23:00:30.332 (1)  <=BE NoData
23:00:30.334 (1)  <=BE ErrorMessage(ERROR: table "testbatch" does not exist
Location: File: tablecmds.c, Routine: DropErrorMsgNonExistent, Line: 727
Server SQLState: 42P01)
23:00:30.335 (1)  <=BE ReadyForQuery(I)
23:00:30.335 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@4b9af9a9, maxRows=0, fetchSize=0, flags=21
23:00:30.336 (1)  FE=> Parse(stmt=null,query="CREATE TABLE testbatch (pk INTEGER, col1 INTEGER) ",oids={})
23:00:30.336 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.336 (1)  FE=> Describe(portal=null)
23:00:30.336 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.336 (1)  FE=> Sync
23:00:30.339 (1)  <=BE ParseComplete [null]
23:00:30.339 (1)  <=BE BindComplete [unnamed]
23:00:30.339 (1)  <=BE NoData
23:00:30.339 (1)  <=BE CommandStatus(CREATE TABLE)
23:00:30.339 (1)  <=BE ReadyForQuery(I)
23:00:30.339 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@5387f9e0, maxRows=0, fetchSize=0, flags=21
23:00:30.339 (1)  FE=> Parse(stmt=null,query="INSERT INTO testbatch VALUES (1, 0)",oids={})
23:00:30.340 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.340 (1)  FE=> Describe(portal=null)
23:00:30.340 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.340 (1)  FE=> Sync
23:00:30.341 (1)  <=BE ParseComplete [null]
23:00:30.341 (1)  <=BE BindComplete [unnamed]
23:00:30.341 (1)  <=BE NoData
23:00:30.341 (1)  <=BE CommandStatus(INSERT 0 1)
23:00:30.341 (1)  <=BE ReadyForQuery(I)
23:00:30.341 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@6e5e91e4, maxRows=0, fetchSize=0, flags=21
23:00:30.341 (1)  FE=> Parse(stmt=null,query="DROP TABLE prep CASCADE ",oids={})
23:00:30.341 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.341 (1)  FE=> Describe(portal=null)
23:00:30.342 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.342 (1)  FE=> Sync
23:00:30.343 (1)  <=BE ParseComplete [null]
23:00:30.343 (1)  <=BE BindComplete [unnamed]
23:00:30.343 (1)  <=BE NoData
23:00:30.344 (1)  <=BE CommandStatus(DROP TABLE)
23:00:30.344 (1)  <=BE ReadyForQuery(I)
23:00:30.344 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@2cdf8d8a, maxRows=0, fetchSize=0, flags=21
23:00:30.344 (1)  FE=> Parse(stmt=null,query="CREATE TABLE prep (a integer, b integer) ",oids={})
23:00:30.344 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.344 (1)  FE=> Describe(portal=null)
23:00:30.344 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.344 (1)  FE=> Sync
23:00:30.345 (1)  <=BE ParseComplete [null]
23:00:30.345 (1)  <=BE BindComplete [unnamed]
23:00:30.345 (1)  <=BE NoData
23:00:30.345 (1)  <=BE CommandStatus(CREATE TABLE)
23:00:30.346 (1)  <=BE ReadyForQuery(I)
23:00:30.346 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@30946e09, maxRows=0, fetchSize=0, flags=1
23:00:30.346 (1)  FE=> Parse(stmt=null,query="BEGIN",oids={})
23:00:30.346 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.346 (1)  FE=> Execute(portal=null,limit=0)
23:00:30.347 (1)  FE=> Parse(stmt=null,query="BEGIN",oids={})
23:00:30.347 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.347 (1)  FE=> Describe(portal=null)
23:00:30.347 (1)  FE=> Execute(portal=null,limit=0)
23:00:30.347 (1)  FE=> Sync
23:00:30.348 (1)  <=BE ParseComplete [null]
23:00:30.348 (1)  <=BE BindComplete [unnamed]
23:00:30.348 (1)  <=BE CommandStatus(BEGIN)
23:00:30.348 (1)  <=BE ParseComplete [null]
23:00:30.348 (1)  <=BE BindComplete [unnamed]
23:00:30.348 (1)  <=BE NoData
23:00:30.348 (1)  <=BE NoticeResponse(WARNING: there is already a transaction in progress
Location: File: xact.c, Routine: BeginTransactionBlock, Line: 3279
Server SQLState: 25001)
23:00:30.348 (1)  <=BE CommandStatus(BEGIN)
23:00:30.348 (1)  <=BE ReadyForQuery(T)
23:00:30.351 (1) batch execute 6 queries, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$BatchResultHandler@5cb0d902, maxRows=0, fetchSize=0, flags=516
23:00:30.351 (1)  FE=> Parse(stmt=S_1,query="insert into prep(a,b)  values($1::int4,$2)",oids={23,23})
23:00:30.351 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:00:30.351 (1)  FE=> Describe(portal=null)
23:00:30.351 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.351 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:00:30.351 (1)  FE=> Describe(portal=null)
23:00:30.351 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.352 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:00:30.352 (1)  FE=> Describe(portal=null)
23:00:30.352 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.352 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:00:30.352 (1)  FE=> Describe(portal=null)
23:00:30.352 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.352 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:00:30.352 (1)  FE=> Describe(portal=null)
23:00:30.352 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.352 (1)  FE=> Parse(stmt=S_2,query="insert into prep(a,b)  values($1::int4,$2)",oids={1043,23})
23:00:30.353 (1)  FE=> Bind(stmt=S_2,portal=null,$1=<'1'>,$2=<2>)
23:00:30.353 (1)  FE=> Describe(portal=null)
23:00:30.353 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.353 (1)  FE=> Sync
23:00:30.354 (1)  <=BE ParseComplete [S_2]
23:00:30.354 (1)  <=BE BindComplete [unnamed]
23:00:30.354 (1)  <=BE NoData
23:00:30.354 (1)  <=BE CommandStatus(INSERT 0 1)
23:00:30.355 (1)  <=BE BindComplete [unnamed]
23:00:30.355 (1)  <=BE NoData
23:00:30.355 (1)  <=BE CommandStatus(INSERT 0 1)
23:00:30.355 (1)  <=BE BindComplete [unnamed]
23:00:30.355 (1)  <=BE NoData
23:00:30.355 (1)  <=BE CommandStatus(INSERT 0 1)
23:00:30.355 (1)  <=BE BindComplete [unnamed]
23:00:30.355 (1)  <=BE NoData
23:00:30.355 (1)  <=BE CommandStatus(INSERT 0 1)
23:00:30.355 (1)  <=BE BindComplete [unnamed]
23:00:30.355 (1)  <=BE NoData
23:00:30.356 (1)  <=BE CommandStatus(INSERT 0 1)
23:00:30.356 (1)  <=BE ParseComplete [S_2]
23:00:30.356 (1)  <=BE BindComplete [unnamed]
23:00:30.356 (1)  <=BE NoData
23:00:30.356 (1)  <=BE CommandStatus(INSERT 0 1)
23:00:30.356 (1)  <=BE ReadyForQuery(T)
23:00:30.356 (1) batch execute 1 queries, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$BatchResultHandler@5ef04b5, maxRows=0, fetchSize=0, flags=516
23:00:30.356 (1)  FE=> CloseStatement(S_2)
23:00:30.356 (1)  FE=> Bind(stmt=S_2,portal=null,$1=<'2'>,$2=<2>)
23:00:30.356 (1)  FE=> Describe(portal=null)
23:00:30.356 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.357 (1)  FE=> Sync
23:00:30.357 (1)  <=BE CloseComplete
23:00:30.357 (1)  <=BE ErrorMessage(ERROR: prepared statement "S_2" does not exist
Location: File: prepare.c, Routine: FetchPreparedStatement, Line: 505
Server SQLState: 26000)
23:00:30.358 (1)  <=BE ReadyForQuery(E)
23:00:30.358 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Connection$TransactionCommandHandler@5f4da5c3, maxRows=0, fetchSize=0, flags=22
23:00:30.358 (1)  FE=> Parse(stmt=S_3,query="COMMIT",oids={})
23:00:30.358 (1)  FE=> Bind(stmt=S_3,portal=null)
23:00:30.358 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.358 (1)  FE=> Sync
23:00:30.359 (1)  <=BE ParseComplete [S_3]
23:00:30.359 (1)  <=BE BindComplete [unnamed]
23:00:30.359 (1)  <=BE CommandStatus(ROLLBACK)
23:00:30.359 (1)  <=BE ReadyForQuery(I)
23:00:30.359 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@14514713, maxRows=0, fetchSize=0, flags=21
23:00:30.359 (1)  FE=> Parse(stmt=null,query="DROP TABLE testbatch CASCADE ",oids={})
23:00:30.359 (1)  FE=> Bind(stmt=null,portal=null)
23:00:30.359 (1)  FE=> Describe(portal=null)
23:00:30.359 (1)  FE=> Execute(portal=null,limit=1)
23:00:30.359 (1)  FE=> Sync
23:00:30.360 (1)  <=BE ParseComplete [null]
23:00:30.360 (1)  <=BE BindComplete [unnamed]
23:00:30.360 (1)  <=BE NoData
23:00:30.361 (1)  <=BE CommandStatus(DROP TABLE)
23:00:30.361 (1)  <=BE ReadyForQuery(I)
23:00:30.361 (1)  FE=> Terminate

org.postgresql.util.PSQLException: ERROR: prepared statement "S_2" does not exist
Location: File: prepare.c, Routine: FetchPreparedStatement, Line: 505
Server SQLState: 26000

at org.postgresql.core.v3.QueryExecutorImpl.receiveErrorResponse(QueryExecutorImpl.java:2183)
at org.postgresql.core.v3.QueryExecutorImpl.processResults(QueryExecutorImpl.java:1912)
at org.postgresql.core.v3.QueryExecutorImpl.execute(QueryExecutorImpl.java:338)
at org.postgresql.jdbc2.AbstractJdbc2Statement.executeBatch(AbstractJdbc2Statement.java:2959)
at org.postgresql.test.jdbc2.BatchExecuteTest.testBatchWithAlternatingTypes(BatchExecuteTest.java:457)
     */

    /*
    Trace after the fix:
23:15:33.776 (1) PostgreSQL 9.4 JDBC4.1 (build 1206)
23:15:33.785 (1) Trying to establish a protocol version 3 connection to localhost:5432
23:15:33.804 (1) Receive Buffer Size is 408300
23:15:33.804 (1) Send Buffer Size is 146988
23:15:33.813 (1)  FE=> StartupPacket(user=postgres, database=vladimirsitnikov, client_encoding=UTF8, DateStyle=ISO, TimeZone=Europe/Volgograd, extra_float_digits=2)
23:15:33.816 (1)  <=BE AuthenticationOk
23:15:33.827 (1)  <=BE ParameterStatus(application_name = )
23:15:33.827 (1)  <=BE ParameterStatus(client_encoding = UTF8)
23:15:33.827 (1)  <=BE ParameterStatus(DateStyle = ISO, DMY)
23:15:33.827 (1)  <=BE ParameterStatus(integer_datetimes = on)
23:15:33.827 (1)  <=BE ParameterStatus(IntervalStyle = postgres)
23:15:33.827 (1)  <=BE ParameterStatus(is_superuser = on)
23:15:33.827 (1)  <=BE ParameterStatus(server_encoding = SQL_ASCII)
23:15:33.828 (1)  <=BE ParameterStatus(server_version = 9.4.5)
23:15:33.828 (1)  <=BE ParameterStatus(session_authorization = postgres)
23:15:33.828 (1)  <=BE ParameterStatus(standard_conforming_strings = on)
23:15:33.828 (1)  <=BE ParameterStatus(TimeZone = Europe/Volgograd)
23:15:33.828 (1)  <=BE BackendKeyData(pid=82726,ckey=1081936502)
23:15:33.828 (1)  <=BE ReadyForQuery(I)
23:15:33.832 (1) simple execute, handler=org.postgresql.core.SetupQueryRunner$SimpleResultHandler@531d72ca, maxRows=0, fetchSize=0, flags=23
23:15:33.832 (1)  FE=> Parse(stmt=null,query="SET extra_float_digits = 3",oids={})
23:15:33.833 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.833 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.834 (1)  FE=> Sync
23:15:33.836 (1)  <=BE ParseComplete [null]
23:15:33.836 (1)  <=BE BindComplete [unnamed]
23:15:33.836 (1)  <=BE CommandStatus(SET)
23:15:33.836 (1)  <=BE ReadyForQuery(I)
23:15:33.837 (1)     compatible = 90400
23:15:33.837 (1)     loglevel = 10
23:15:33.837 (1)     prepare threshold = 5
23:15:33.839 (1)     types using binary send = TIMESTAMPTZ,UUID,INT2_ARRAY,INT4_ARRAY,BYTEA,TEXT_ARRAY,TIMETZ,INT8,INT2,INT4,VARCHAR_ARRAY,INT8_ARRAY,POINT,TIMESTAMP,TIME,BOX,FLOAT4,FLOAT8,FLOAT4_ARRAY,FLOAT8_ARRAY
23:15:33.841 (1)     types using binary receive = TIMESTAMPTZ,UUID,INT2_ARRAY,INT4_ARRAY,BYTEA,TEXT_ARRAY,TIMETZ,INT8,INT2,INT4,VARCHAR_ARRAY,INT8_ARRAY,POINT,DATE,TIMESTAMP,TIME,BOX,FLOAT4,FLOAT8,FLOAT4_ARRAY,FLOAT8_ARRAY
23:15:33.841 (1)     integer date/time = true
23:15:33.899 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@255316f2, maxRows=0, fetchSize=0, flags=21
23:15:33.899 (1)  FE=> Parse(stmt=null,query="DROP TABLE testbatch CASCADE ",oids={})
23:15:33.899 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.899 (1)  FE=> Describe(portal=null)
23:15:33.900 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.900 (1)  FE=> Sync
23:15:33.900 (1)  <=BE ParseComplete [null]
23:15:33.900 (1)  <=BE BindComplete [unnamed]
23:15:33.900 (1)  <=BE NoData
23:15:33.905 (1)  <=BE ErrorMessage(ERROR: table "testbatch" does not exist
Location: File: tablecmds.c, Routine: DropErrorMsgNonExistent, Line: 727
Server SQLState: 42P01)
23:15:33.906 (1)  <=BE ReadyForQuery(I)
23:15:33.906 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@4b9af9a9, maxRows=0, fetchSize=0, flags=21
23:15:33.906 (1)  FE=> Parse(stmt=null,query="CREATE TABLE testbatch (pk INTEGER, col1 INTEGER) ",oids={})
23:15:33.907 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.907 (1)  FE=> Describe(portal=null)
23:15:33.907 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.907 (1)  FE=> Sync
23:15:33.911 (1)  <=BE ParseComplete [null]
23:15:33.912 (1)  <=BE BindComplete [unnamed]
23:15:33.912 (1)  <=BE NoData
23:15:33.912 (1)  <=BE CommandStatus(CREATE TABLE)
23:15:33.912 (1)  <=BE ReadyForQuery(I)
23:15:33.912 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@5387f9e0, maxRows=0, fetchSize=0, flags=21
23:15:33.912 (1)  FE=> Parse(stmt=null,query="INSERT INTO testbatch VALUES (1, 0)",oids={})
23:15:33.913 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.913 (1)  FE=> Describe(portal=null)
23:15:33.913 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.913 (1)  FE=> Sync
23:15:33.914 (1)  <=BE ParseComplete [null]
23:15:33.914 (1)  <=BE BindComplete [unnamed]
23:15:33.914 (1)  <=BE NoData
23:15:33.914 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.914 (1)  <=BE ReadyForQuery(I)
23:15:33.914 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@6e5e91e4, maxRows=0, fetchSize=0, flags=21
23:15:33.914 (1)  FE=> Parse(stmt=null,query="DROP TABLE prep CASCADE ",oids={})
23:15:33.914 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.914 (1)  FE=> Describe(portal=null)
23:15:33.914 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.915 (1)  FE=> Sync
23:15:33.916 (1)  <=BE ParseComplete [null]
23:15:33.916 (1)  <=BE BindComplete [unnamed]
23:15:33.916 (1)  <=BE NoData
23:15:33.917 (1)  <=BE CommandStatus(DROP TABLE)
23:15:33.917 (1)  <=BE ReadyForQuery(I)
23:15:33.917 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@2cdf8d8a, maxRows=0, fetchSize=0, flags=21
23:15:33.917 (1)  FE=> Parse(stmt=null,query="CREATE TABLE prep (a integer, b integer) ",oids={})
23:15:33.917 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.917 (1)  FE=> Describe(portal=null)
23:15:33.917 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.917 (1)  FE=> Sync
23:15:33.919 (1)  <=BE ParseComplete [null]
23:15:33.919 (1)  <=BE BindComplete [unnamed]
23:15:33.919 (1)  <=BE NoData
23:15:33.919 (1)  <=BE CommandStatus(CREATE TABLE)
23:15:33.919 (1)  <=BE ReadyForQuery(I)
23:15:33.919 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@30946e09, maxRows=0, fetchSize=0, flags=1
23:15:33.919 (1)  FE=> Parse(stmt=null,query="BEGIN",oids={})
23:15:33.920 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.920 (1)  FE=> Execute(portal=null,limit=0)
23:15:33.920 (1)  FE=> Parse(stmt=null,query="BEGIN",oids={})
23:15:33.920 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.920 (1)  FE=> Describe(portal=null)
23:15:33.920 (1)  FE=> Execute(portal=null,limit=0)
23:15:33.921 (1)  FE=> Sync
23:15:33.921 (1)  <=BE ParseComplete [null]
23:15:33.921 (1)  <=BE BindComplete [unnamed]
23:15:33.921 (1)  <=BE CommandStatus(BEGIN)
23:15:33.921 (1)  <=BE ParseComplete [null]
23:15:33.921 (1)  <=BE BindComplete [unnamed]
23:15:33.921 (1)  <=BE NoData
23:15:33.921 (1)  <=BE NoticeResponse(WARNING: there is already a transaction in progress
Location: File: xact.c, Routine: BeginTransactionBlock, Line: 3279
Server SQLState: 25001)
23:15:33.922 (1)  <=BE CommandStatus(BEGIN)
23:15:33.922 (1)  <=BE ReadyForQuery(T)
23:15:33.924 (1) batch execute 6 queries, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$BatchResultHandler@5cb0d902, maxRows=0, fetchSize=0, flags=516
23:15:33.924 (1)  FE=> Parse(stmt=S_1,query="insert into prep(a,b)  values($1::int4,$2)",oids={23,23})
23:15:33.925 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:15:33.925 (1)  FE=> Describe(portal=null)
23:15:33.925 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.925 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:15:33.925 (1)  FE=> Describe(portal=null)
23:15:33.925 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.925 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:15:33.925 (1)  FE=> Describe(portal=null)
23:15:33.925 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.925 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:15:33.926 (1)  FE=> Describe(portal=null)
23:15:33.926 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.926 (1)  FE=> Bind(stmt=S_1,portal=null,$1=<2>,$2=<2>)
23:15:33.926 (1)  FE=> Describe(portal=null)
23:15:33.926 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.926 (1)  FE=> CloseStatement(S_1)
23:15:33.926 (1)  FE=> Parse(stmt=S_2,query="insert into prep(a,b)  values($1::int4,$2)",oids={1043,23})
23:15:33.926 (1)  FE=> Bind(stmt=S_2,portal=null,$1=<'1'>,$2=<2>)
23:15:33.927 (1)  FE=> Describe(portal=null)
23:15:33.927 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.927 (1)  FE=> Sync
23:15:33.928 (1)  <=BE ParseComplete [S_2]
23:15:33.928 (1)  <=BE BindComplete [unnamed]
23:15:33.928 (1)  <=BE NoData
23:15:33.928 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.928 (1)  <=BE BindComplete [unnamed]
23:15:33.928 (1)  <=BE NoData
23:15:33.928 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.929 (1)  <=BE BindComplete [unnamed]
23:15:33.929 (1)  <=BE NoData
23:15:33.929 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.929 (1)  <=BE BindComplete [unnamed]
23:15:33.929 (1)  <=BE NoData
23:15:33.929 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.929 (1)  <=BE BindComplete [unnamed]
23:15:33.929 (1)  <=BE NoData
23:15:33.929 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.929 (1)  <=BE CloseComplete
23:15:33.929 (1)  <=BE ParseComplete [S_2]
23:15:33.929 (1)  <=BE BindComplete [unnamed]
23:15:33.929 (1)  <=BE NoData
23:15:33.930 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.930 (1)  <=BE ReadyForQuery(T)
23:15:33.930 (1) batch execute 1 queries, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$BatchResultHandler@5ef04b5, maxRows=0, fetchSize=0, flags=516
23:15:33.930 (1)  FE=> Bind(stmt=S_2,portal=null,$1=<'2'>,$2=<2>)
23:15:33.930 (1)  FE=> Describe(portal=null)
23:15:33.930 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.930 (1)  FE=> Sync
23:15:33.930 (1)  <=BE BindComplete [unnamed]
23:15:33.931 (1)  <=BE NoData
23:15:33.931 (1)  <=BE CommandStatus(INSERT 0 1)
23:15:33.931 (1)  <=BE ReadyForQuery(T)
23:15:33.931 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@5f4da5c3, maxRows=0, fetchSize=0, flags=1
23:15:33.931 (1)  FE=> Parse(stmt=null,query="COMMIT",oids={})
23:15:33.931 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.931 (1)  FE=> Describe(portal=null)
23:15:33.931 (1)  FE=> Execute(portal=null,limit=0)
23:15:33.931 (1)  FE=> Sync
23:15:33.932 (1)  <=BE ParseComplete [null]
23:15:33.932 (1)  <=BE BindComplete [unnamed]
23:15:33.932 (1)  <=BE NoData
23:15:33.932 (1)  <=BE CommandStatus(COMMIT)
23:15:33.932 (1)  <=BE ReadyForQuery(I)
23:15:33.932 (1) simple execute, handler=org.postgresql.jdbc2.AbstractJdbc2Statement$StatementResultHandler@443b7951, maxRows=0, fetchSize=0, flags=21
23:15:33.932 (1)  FE=> Parse(stmt=null,query="DROP TABLE testbatch CASCADE ",oids={})
23:15:33.933 (1)  FE=> Bind(stmt=null,portal=null)
23:15:33.933 (1)  FE=> Describe(portal=null)
23:15:33.933 (1)  FE=> Execute(portal=null,limit=1)
23:15:33.933 (1)  FE=> Sync
23:15:33.934 (1)  <=BE ParseComplete [null]
23:15:33.934 (1)  <=BE BindComplete [unnamed]
23:15:33.934 (1)  <=BE NoData
23:15:33.934 (1)  <=BE CommandStatus(DROP TABLE)
23:15:33.934 (1)  <=BE ReadyForQuery(I)
23:15:33.934 (1)  FE=> Terminate
<<<<<<< HEAD
     */
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
      Assert.fail("Expecting BatchUpdateException as key-2 is duplicated in batchUpdCnt.id. "
          + " executeBatch returned " + Arrays.toString(batchResult));
    } catch (BatchUpdateException ex) {
      batchResult = ex.getUpdateCounts();
    } finally {
      TestUtil.closeQuietly(batchSt);
    }

    int newCount = getBatchUpdCount();
    if (newCount == 2) {
      // key-1 did succeed
      Assert.assertTrue("batchResult[0] should be 1 or SUCCESS_NO_INFO since 'key-1' was inserted,"
          + " actual result is " + Arrays.toString(batchResult),
          batchResult[0] == 1 || batchResult[0] == Statement.SUCCESS_NO_INFO);
    } else {
      Assert.assertTrue("batchResult[0] should be 0 or EXECUTE_FAILED since 'key-1' was NOT inserted,"
              + " actual result is " + Arrays.toString(batchResult),
          batchResult[0] == 0 || batchResult[0] == Statement.EXECUTE_FAILED);
    }

    Assert.assertEquals("'key-2' insertion should have Assert.failed",
        Statement.EXECUTE_FAILED, batchResult[1]);
  }

  private int getBatchUpdCount() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select count(*) from batchUpdCnt");
    ResultSet rs = ps.executeQuery();
    Assert.assertTrue("count(*) must return 1 row", rs.next());
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

      Assert.assertNotNull(outcome);
      Assert.assertEquals(2, outcome.length);
      int rowsInserted = insertRewrite ? Statement.SUCCESS_NO_INFO : 1;
      Assert.assertEquals(rowsInserted, outcome[0]);
      Assert.assertEquals(rowsInserted, outcome[1]);
    } catch (SQLException sqle) {
      Assert.fail("Failed to execute two statements added to a batch. Reason:" + sqle.getMessage());
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
      Assert.assertNotNull(outcome);
      Assert.assertEquals(1, outcome.length);
      Assert.assertEquals(2, outcome[0]);
    } catch (SQLException sqle) {
      Assert.fail("Failed to execute two statements added to a batch. Reason:" + sqle.getMessage());
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
      Assert.assertEquals(
          "Inserting two multi-valued statements with two rows each. Expecting {2, 2} rows inserted (or SUCCESS_NO_INFO)",
          Arrays.toString(new int[] { rowsInserted, rowsInserted }),
          Arrays.toString(outcome));
    } catch (SQLException sqle) {
      Assert.fail("Failed to execute two statements added to a batch. Reason:" + sqle.getMessage());
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
    Assert.assertEquals(
        message,
        Arrays.toString(clone),
        Arrays.toString(actual));
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
      Assert.assertTrue(
          "More than 1 row is inserted via executeBatch, it should lead to multiple server statements, thus the statements should be server-prepared",
          ((PGStatement) ps).isUseServerPrepare());
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
        Assert.assertTrue(
            "prepareThreshold=" + prepareThreshold
                + " thus the statement should be server-prepared",
            ((PGStatement) ps).isUseServerPrepare());
      } else {
        Assert.assertFalse(
            "Just one row inserted via executeBatch, prepareThreshold=" + prepareThreshold
                + " thus the statement should not be server-prepared",
            ((PGStatement) ps).isUseServerPrepare());
      }
      assertBatchResult("1 rows inserted via batch", new int[]{1}, actual);
    } finally {
      TestUtil.closeQuietly(ps);
    }
  }
}
