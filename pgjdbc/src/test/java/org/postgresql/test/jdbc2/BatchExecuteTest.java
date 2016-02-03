/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import java.sql.BatchUpdateException;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/*
 * TODO tests that can be added to this test case - SQLExceptions chained to a BatchUpdateException
 * - test PreparedStatement as thoroughly as Statement
 */

/*
 * Test case for Statement.batchExecute()
 */
public class BatchExecuteTest extends BaseTest {

  public BatchExecuteTest(String name) {
    super(name);
  }

  // Set up the fixture for this testcase: a connection to a database with
  // a table for this test.
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Statement stmt = con.createStatement();

    // Drop the test table if it already exists for some reason. It is
    // not an error if it doesn't exist.
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER");

    stmt.executeUpdate("INSERT INTO testbatch VALUES (1, 0)");

    TestUtil.createTable(con, "prep", "a integer, b integer");

    TestUtil.createTable(con, "batchUpdCnt", "id varchar(512) primary key, data varchar(512)");
    stmt.executeUpdate("INSERT INTO batchUpdCnt(id) VALUES ('key-2')");

    stmt.close();

    // Generally recommended with batch updates. By default we run all
    // tests in this test case with autoCommit disabled.
    con.setAutoCommit(false);
  }

  // Tear down the fixture for this test case.
  @Override
  protected void tearDown() throws SQLException {
    con.setAutoCommit(true);

    TestUtil.dropTable(con, "testbatch");
    super.tearDown();
  }

  public void testSupportsBatchUpdates() throws Exception {
    DatabaseMetaData dbmd = con.getMetaData();
    assertTrue(dbmd.supportsBatchUpdates());
  }

  public void testEmptyClearBatch() throws Exception {
    Statement stmt = con.createStatement();
    stmt.clearBatch(); // No-op.

    PreparedStatement ps = con.prepareStatement("SELECT ?");
    ps.clearBatch(); // No-op.
  }

  private void assertCol1HasValue(int expected) throws Exception {
    Statement getCol1 = con.createStatement();

    ResultSet rs = getCol1.executeQuery("SELECT col1 FROM testbatch WHERE pk = 1");
    assertTrue(rs.next());

    int actual = rs.getInt("col1");

    assertEquals(expected, actual);

    assertEquals(false, rs.next());

    rs.close();
    getCol1.close();
  }

  public void testExecuteEmptyBatch() throws Exception {
    Statement stmt = con.createStatement();
    int[] updateCount = stmt.executeBatch();
    assertEquals(0, updateCount.length);

    stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
    stmt.clearBatch();
    updateCount = stmt.executeBatch();
    assertEquals(0, updateCount.length);
    stmt.close();
  }

  public void testClearBatch() throws Exception {
    Statement stmt = con.createStatement();

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

    stmt.close();
  }

  public void testSelectInBatch() throws Exception {
    Statement stmt = con.createStatement();

    stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
    stmt.addBatch("SELECT col1 FROM testbatch WHERE pk = 1");
    stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");

    // There's no reason to fail
    int[] updateCounts = stmt.executeBatch();

    assertTrue("First update should succeed, thus updateCount should be 1 or SUCCESS_NO_INFO"
            + ", actual value: " + updateCounts[0],
        updateCounts[0] == 1 || updateCounts[0] == Statement.SUCCESS_NO_INFO);
    assertTrue("For SELECT, number of modified rows should be either 0 or SUCCESS_NO_INFO"
            + ", actual value: " + updateCounts[1],
        updateCounts[1] == 0 || updateCounts[1] == Statement.SUCCESS_NO_INFO);
    assertTrue("Second update should succeed, thus updateCount should be 1 or SUCCESS_NO_INFO"
            + ", actual value: " + updateCounts[2],
        updateCounts[2] == 1 || updateCounts[2] == Statement.SUCCESS_NO_INFO);

    stmt.close();
  }

  public void testSelectInBatchThrowsAutoCommit() throws Exception {
    con.setAutoCommit(true);
    testSelectInBatchThrows();
  }

  public void testSelectInBatchThrows() throws Exception {
    Statement stmt = con.createStatement();

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
    assertEquals("testbatch.col1 should not be updated since error happened in batch",
        oldValue, newValue);

    assertEquals("All rows should be marked as EXECUTE_FAILED",
        Arrays.toString(new int[]{Statement.EXECUTE_FAILED, Statement.EXECUTE_FAILED,
            Statement.EXECUTE_FAILED}),
        Arrays.toString(updateCounts));

    stmt.close();
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

  public void testStringAddBatchOnPreparedStatement() throws Exception {
    PreparedStatement pstmt =
        con.prepareStatement("UPDATE testbatch SET col1 = col1 + ? WHERE PK = ?");
    pstmt.setInt(1, 1);
    pstmt.setInt(2, 1);
    pstmt.addBatch();

    try {
      pstmt.addBatch("UPDATE testbatch SET col1 = 3");
      fail(
          "Should have thrown an exception about using the string addBatch method on a prepared statement.");
    } catch (SQLException sqle) {
    }

    pstmt.close();
  }

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

    stmt.close();
  }

  public void testWarningsAreCleared() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.addBatch("CREATE TEMP TABLE unused (a int primary key)");
    stmt.executeBatch();
    // Execute an empty batch to clear warnings.
    stmt.executeBatch();
    assertNull(stmt.getWarnings());
    stmt.close();
  }

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
    rs.close();
    stmt.close();
  }

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
    rs.close();
    stmt.close();
  }

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
   * setString call) the batch may fail with:
   *
   * "Received resultset tuples, but no field structure for them"
   *
   * at org.postgresql.core.v3.QueryExecutorImpl.processResults
   *
   * Prior to 245b388 it would instead fail with a NullPointerException in
   * AbstractJdbc2ResultSet.checkColumnIndex
   *
   * The cause is complicated. The failure arises because the query gets re-planned mid-batch. This
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

  /**
   * Tests {@link PreparedStatement#addBatch} in case types of parameters change from one batch to
   * another. Change of the datatypes causes re-prepare server-side statement, thus exactly the same
   * query object might have different statement names.
   */
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
     */
  }

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
      batchSt.close();
    }

    int newCount = getBatchUpdCount();
    if (newCount == 2) {
      // key-1 did succeed
      assertTrue("batchResult[0] should be 1 or SUCCESS_NO_INFO since 'key-1' was inserted,"
          + " actual result is " + Arrays.toString(batchResult),
          batchResult[0] == 1 || batchResult[0] == Statement.SUCCESS_NO_INFO);
    } else {
      assertTrue("batchResult[0] should be 0 or EXECUTE_FAILED since 'key-1' was NOT inserted,"
              + " actual result is " + Arrays.toString(batchResult),
          batchResult[0] == 0 || batchResult[0] == Statement.EXECUTE_FAILED);
    }

    assertEquals("'key-2' insertion should have failed",
        batchResult[1], Statement.EXECUTE_FAILED);
  }

  private int getBatchUpdCount() throws SQLException {
    PreparedStatement ps = con.prepareStatement("select count(*) from batchUpdCnt");
    ResultSet rs = ps.executeQuery();
    assertTrue("count(*) must return 1 row", rs.next());
    return rs.getInt(1);
  }
}
