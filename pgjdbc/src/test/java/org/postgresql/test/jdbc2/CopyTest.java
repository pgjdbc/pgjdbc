/*-------------------------------------------------------------------------
*
* Copyright (c) 2008-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.CopyOut;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import junit.framework.TestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * @author kato@iki.fi
 */
public class CopyTest extends TestCase {

  private Connection con;
  private CopyManager copyAPI;
  private String[] origData =
      {"First Row\t1\t1.10\n", // 0's required to match DB output for numeric(5,2)
          "Second Row\t2\t-22.20\n",
          "\\N\t\\N\t\\N\n",
          "\t4\t444.40\n"};
  private int dataRows = origData.length;

  public CopyTest(String name) {
    super(name);
  }

  private byte[] getData(String[] origData) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(buf);
    for (int i = 0; i < origData.length; i++) {
      ps.print(origData[i]);
    }
    return buf.toByteArray();
  }

  protected void setUp() throws Exception {


    con = TestUtil.openDB();

    TestUtil.createTable(con, "copytest", "stringvalue text, intvalue int, numvalue numeric(5,2)");

    copyAPI = ((PGConnection) con).getCopyAPI();
  }

  protected void tearDown() throws Exception {
    TestUtil.closeDB(con);

    // one of the tests will render the existing connection broken,
    // so we need to drop the table on a fresh one.
    con = TestUtil.openDB();
    try {
      TestUtil.dropTable(con, "copytest");
    } finally {
      con.close();
    }
  }

  private int getCount() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT count(*) FROM copytest");
    rs.next();
    int result = rs.getInt(1);
    rs.close();
    return result;
  }

  public void testCopyInByRow() throws SQLException {
    String sql = "COPY copytest FROM STDIN";
    CopyIn cp = copyAPI.copyIn(sql);
    for (int i = 0; i < origData.length; i++) {
      byte[] buf = origData[i].getBytes();
      cp.writeToCopy(buf, 0, buf.length);
    }

    long count1 = cp.endCopy();
    long count2 = cp.getHandledRowCount();
    long expectedResult = -1;
    if (TestUtil.haveMinimumServerVersion(con, "8.2")) {
      expectedResult = dataRows;
    }
    assertEquals(expectedResult, count1);
    assertEquals(expectedResult, count2);

    try {
      cp.cancelCopy();
    } catch (SQLException se) { // should fail with obsolete operation
      if (!PSQLState.OBJECT_NOT_IN_STATE.getState().equals(se.getSQLState())) {
        fail("should have thrown object not in state exception.");
      }
    }
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  public void testCopyInAsOutputStream() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    OutputStream os = new PGCopyOutputStream((PGConnection) con, sql, 1000);
    for (int i = 0; i < origData.length; i++) {
      byte[] buf = origData[i].getBytes();
      os.write(buf);
    }
    os.close();
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  public void testCopyInFromInputStream() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    copyAPI.copyIn(sql, new ByteArrayInputStream(getData(origData)), 3);
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  public void testCopyInFromStreamFail() throws SQLException {
    String sql = "COPY copytest FROM STDIN";
    try {
      copyAPI.copyIn(sql, new InputStream() {
        public int read() {
          throw new RuntimeException("COPYTEST");
        }
      }, 3);
    } catch (Exception e) {
      if (e.toString().indexOf("COPYTEST") == -1) {
        fail("should have failed trying to read from our bogus stream.");
      }
    }
    int rowCount = getCount();
    assertEquals(0, rowCount);
  }

  public void testCopyInFromReader() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    copyAPI.copyIn(sql, new StringReader(new String(getData(origData))), 3);
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  public void testSkipping() {
    String sql = "COPY copytest FROM STDIN";
    String at = "init";
    int rowCount = -1;
    int skip = 0;
    int skipChar = 1;
    try {
      while (skipChar > 0) {
        at = "buffering";
        InputStream ins = new ByteArrayInputStream(getData(origData));
        at = "skipping";
        ins.skip(skip++);
        skipChar = ins.read();
        at = "copying";
        copyAPI.copyIn(sql, ins, 3);
        at = "using connection after writing copy";
        rowCount = getCount();
      }
    } catch (Exception e) {
      if (!(skipChar == '\t')) // error expected when field separator consumed
      {
        fail("testSkipping at " + at + " round " + skip + ": " + e.toString());
      }
    }
    assertEquals(dataRows * (skip - 1), rowCount);
  }

  public void testCopyOutByRow() throws SQLException, IOException {
    testCopyInByRow(); // ensure we have some data.
    String sql = "COPY copytest TO STDOUT";
    CopyOut cp = copyAPI.copyOut(sql);
    int count = 0;
    byte buf[];
    while ((buf = cp.readFromCopy()) != null) {
      count++;
    }
    assertEquals(false, cp.isActive());
    assertEquals(dataRows, count);

    long rowCount = cp.getHandledRowCount();
    long expectedResult = -1;
    if (TestUtil.haveMinimumServerVersion(con, "8.2")) {
      expectedResult = dataRows;
    }
    assertEquals(expectedResult, rowCount);

    assertEquals(dataRows, getCount());
  }

  public void testCopyOut() throws SQLException, IOException {
    testCopyInByRow(); // ensure we have some data.
    String sql = "COPY copytest TO STDOUT";
    ByteArrayOutputStream copydata = new ByteArrayOutputStream();
    copyAPI.copyOut(sql, copydata);
    assertEquals(dataRows, getCount());
    // deep comparison of data written and read
    byte[] copybytes = copydata.toByteArray();
    assertTrue(copybytes != null);
    for (int i = 0, l = 0; i < origData.length; i++) {
      byte[] origBytes = origData[i].getBytes();
      assertTrue(origBytes != null);
      assertTrue("Copy is shorter than original", copybytes.length >= l + origBytes.length);
      for (int j = 0; j < origBytes.length; j++, l++) {
        assertEquals("content changed at byte#" + j + ": " + origBytes[j] + copybytes[l],
            origBytes[j], copybytes[l]);
      }
    }
  }

  public void testNonCopyOut() throws SQLException, IOException {
    String sql = "SELECT 1";
    try {
      copyAPI.copyOut(sql, new ByteArrayOutputStream());
      fail("Can't use a non-copy query.");
    } catch (SQLException sqle) {
    }
    // Ensure connection still works.
    assertEquals(0, getCount());
  }

  public void testNonCopyIn() throws SQLException, IOException {
    String sql = "SELECT 1";
    try {
      copyAPI.copyIn(sql, new ByteArrayInputStream(new byte[0]));
      fail("Can't use a non-copy query.");
    } catch (SQLException sqle) {
    }
    // Ensure connection still works.
    assertEquals(0, getCount());
  }

  public void testStatementCopyIn() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.execute("COPY copytest FROM STDIN");
      fail("Should have failed because copy doesn't work from a Statement.");
    } catch (SQLException sqle) {
    }
    stmt.close();

    assertEquals(0, getCount());
  }

  public void testStatementCopyOut() throws SQLException {
    testCopyInByRow(); // ensure we have some data.

    Statement stmt = con.createStatement();
    try {
      stmt.execute("COPY copytest TO STDOUT");
      fail("Should have failed because copy doesn't work from a Statement.");
    } catch (SQLException sqle) {
    }
    stmt.close();

    assertEquals(dataRows, getCount());
  }

  public void testCopyQuery() throws SQLException, IOException {
    if (!TestUtil.haveMinimumServerVersion(con, "8.2")) {
      return;
    }

    testCopyInByRow(); // ensure we have some data.

    long count = copyAPI.copyOut("COPY (SELECT generate_series(1,1000)) TO STDOUT",
        new ByteArrayOutputStream());
    assertEquals(1000, count);
  }

  public void testCopyRollback() throws SQLException {
    con.setAutoCommit(false);
    testCopyInByRow();
    con.rollback();
    assertEquals(0, getCount());
  }

  public void testChangeDateStyle() throws SQLException {


    try {
      con.setAutoCommit(false);
      con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      CopyManager manager = con.unwrap(PGConnection.class).getCopyAPI();

      Statement stmt = con.createStatement();

      stmt.execute("SET DateStyle = 'ISO, DMY'");


      // I expect an SQLException
      String sql = "COPY copytest FROM STDIN with xxx (format 'csv')";
      CopyIn cp = manager.copyIn(sql);
      for (int i = 0; i < origData.length; i++) {
        byte[] buf = origData[i].getBytes();
        cp.writeToCopy(buf, 0, buf.length);
      }

      long count1 = cp.endCopy();
      long count2 = cp.getHandledRowCount();
      con.commit();
    } catch (SQLException ex) {

      // the with xxx is a syntax error which shoud return a state of 42601
      // if this fails the 'S' command is not being handled in the copy manager query handler
      assertEquals("42601", ex.getSQLState());
      con.rollback();
    }
  }

  public void testLockReleaseOnCancelFailure() throws SQLException, InterruptedException {
    // This is a fairly complex test because it is testing a
    // deadlock that only occurs when the connection to postgres
    // is broken during a copy operation.  We'll start a copy
    // operation, use pg_terminate_backend to rudely break it,
    // and then cancel.  The test passes if a subsequent operation
    // on the Connection object fails to deadlock.
    con.setAutoCommit(false);

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("select pg_backend_pid()");
    rs.next();
    int pid = rs.getInt(1);
    rs.close();
    stmt.close();

    CopyManager manager = con.unwrap(PGConnection.class).getCopyAPI();
    CopyIn copyIn = manager.copyIn("COPY copytest FROM STDIN with (format 'csv')");
    try {
      killConnection(pid);
      byte[] bunchOfNulls = ",,\n".getBytes();
      while (true) {
        copyIn.writeToCopy(bunchOfNulls, 0, bunchOfNulls.length);
      }
    } catch (SQLException e) {
      acceptIOCause(e);
    } finally {
      if (copyIn.isActive()) {
        try {
          copyIn.cancelCopy();
          fail("cancelCopy should have thrown an exception");
        } catch (SQLException e) {
          acceptIOCause(e);
        }
      }
    }

    // Now we'll execute rollback on another thread so that if the
    // deadlock _does_ occur the testcase doesn't just hange forever.
    Rollback rollback = new Rollback(con);
    rollback.start();
    rollback.join(1000);
    if (rollback.isAlive()) {
      TestCase.fail("rollback did not terminate");
    }
    SQLException rollbackException = rollback.exception();
    if (rollbackException == null) {
      TestCase.fail("rollback should have thrown an exception");
    }
    acceptIOCause(rollbackException);
  }

  private static class Rollback extends Thread {
    private final Connection con;
    private SQLException rollbackException;

    public Rollback(Connection con) {
      setName("Asynchronous rollback");
      setDaemon(true);
      this.con = con;
    }

    public void run() {
      try {
        con.rollback();
      } catch (SQLException e) {
        rollbackException = e;
      }
    }

    public SQLException exception() {
      return rollbackException;
    }
  }

  private void killConnection(int pid) throws SQLException {
    Connection killerCon;
    try {
      killerCon = TestUtil.openPrivilegedDB();
    } catch (Exception e) {
      fail("Unable to open secondary connection to terminate copy");
      return; // persuade Java killerCon will not be used uninitialized
    }
    try {
      PreparedStatement stmt = killerCon.prepareStatement("select pg_terminate_backend(?)");
      stmt.setInt(1, pid);
      stmt.execute();
    } finally {
      killerCon.close();
    }
  }

  private void acceptIOCause(SQLException e) throws SQLException {
    if (!(e.getCause() instanceof IOException)) {
      throw e;
    }
  }

}
