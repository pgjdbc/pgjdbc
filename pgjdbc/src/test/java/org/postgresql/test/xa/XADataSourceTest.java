/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.xa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.xa.PGXADataSource;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Random;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;


public class XADataSourceTest {

  private XADataSource _ds;

  private Connection _conn;
  private boolean connIsSuper;

  private XAConnection xaconn;
  private XAResource xaRes;
  private Connection conn;


  public XADataSourceTest() {
    _ds = new PGXADataSource();
    BaseDataSourceTest.setupDataSource((PGXADataSource) _ds);
  }

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
    assumeTrue(isPreparedTransactionEnabled(_conn));

    // Check if we're operating as a superuser; some tests require it.
    Statement st = _conn.createStatement();
    st.executeQuery("SHOW is_superuser;");
    ResultSet rs = st.getResultSet();
    rs.next(); // One row is guaranteed
    connIsSuper = rs.getBoolean(1); // One col is guaranteed
    st.close();

    TestUtil.createTable(_conn, "testxa1", "foo int");
    TestUtil.createTable(_conn, "testxa2", "foo int primary key");
    TestUtil.createTable(_conn, "testxa3", "foo int references testxa2(foo) deferrable");

    clearAllPrepared();

    xaconn = _ds.getXAConnection();
    xaRes = xaconn.getXAResource();
    conn = xaconn.getConnection();
  }

  private static boolean isPreparedTransactionEnabled(Connection connection) throws SQLException {
    Statement stmt = connection.createStatement();
    ResultSet rs = stmt.executeQuery("SHOW max_prepared_transactions");
    rs.next();
    int mpt = rs.getInt(1);
    rs.close();
    stmt.close();
    return mpt > 0;
  }

  @After
  public void tearDown() throws SQLException {
    try {
      xaconn.close();
    } catch (Exception ignored) {
    }

    clearAllPrepared();
    TestUtil.dropTable(_conn, "testxa3");
    TestUtil.dropTable(_conn, "testxa2");
    TestUtil.dropTable(_conn, "testxa1");
    TestUtil.closeDB(_conn);

  }

  private void clearAllPrepared() throws SQLException {
    Statement st = _conn.createStatement();
    try {
      ResultSet rs = st.executeQuery(
          "SELECT x.gid, x.owner = current_user "
              + "FROM pg_prepared_xacts x "
              + "WHERE x.database = current_database()");

      Statement st2 = _conn.createStatement();
      while (rs.next()) {
        // TODO: This should really use org.junit.Assume once we move to JUnit 4
        assertTrue("Only prepared xacts owned by current user may be present in db",
            rs.getBoolean(2));
        st2.executeUpdate("ROLLBACK PREPARED '" + rs.getString(1) + "'");
      }
      st2.close();
    } finally {
      st.close();
    }
  }

  static class CustomXid implements Xid {
    private static Random rand = new Random(System.currentTimeMillis());
    byte[] gtrid = new byte[Xid.MAXGTRIDSIZE];
    byte[] bqual = new byte[Xid.MAXBQUALSIZE];

    CustomXid(int i) {
      rand.nextBytes(gtrid);
      gtrid[0] = (byte) i;
      gtrid[1] = (byte) i;
      gtrid[2] = (byte) i;
      gtrid[3] = (byte) i;
      gtrid[4] = (byte) i;
      bqual[0] = 4;
      bqual[1] = 5;
      bqual[2] = 6;
    }

    @Override
    public int getFormatId() {
      return 0;
    }


    @Override
    public byte[] getGlobalTransactionId() {
      return gtrid;
    }

    @Override
    public byte[] getBranchQualifier() {
      return bqual;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof Xid)) {
        return false;
      }

      Xid other = (Xid) o;
      if (other.getFormatId() != this.getFormatId()) {
        return false;
      }
      if (!Arrays.equals(other.getBranchQualifier(), this.getBranchQualifier())) {
        return false;
      }
      if (!Arrays.equals(other.getGlobalTransactionId(), this.getGlobalTransactionId())) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + Arrays.hashCode(getBranchQualifier());
      result = prime * result + getFormatId();
      result = prime * result + Arrays.hashCode(getGlobalTransactionId());
      return result;
    }
  }

  /*
   * Check that the equals method works for the connection wrapper returned by
   * PGXAConnection.getConnection().
   */
  @Test
  public void testWrapperEquals() throws Exception {
    assertTrue("Wrappers should be equal", conn.equals(conn));
  }

  @Test
  public void testOnePhase() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);
  }

  @Test
  public void testTwoPhaseCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.commit(xid, false);
  }

  @Test
  public void testCloseBeforeCommit() throws Exception {
    Xid xid = new CustomXid(5);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
    conn.close();
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
  }

  @Test
  public void testRecover() throws Exception {
    Xid xid = new CustomXid(12345);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    {
      Xid[] recoveredXidArray = xaRes.recover(XAResource.TMSTARTRSCAN);

      boolean recoveredXid = false;

      for (Xid aRecoveredXidArray : recoveredXidArray) {
        if (xid.equals(aRecoveredXidArray)) {
          recoveredXid = true;
          break;
        }
      }

      assertTrue("Did not recover prepared xid", recoveredXid);
      assertEquals(0, xaRes.recover(XAResource.TMNOFLAGS).length);
    }

    xaRes.rollback(xid);

    {
      Xid[] recoveredXidArray = xaRes.recover(XAResource.TMSTARTRSCAN);

      boolean recoveredXid = false;

      for (Xid aRecoveredXidArray : recoveredXidArray) {
        if (xaRes.equals(aRecoveredXidArray)) {
          recoveredXid = true;
          break;
        }
      }

      assertFalse("Recovered rolled back xid", recoveredXid);
    }
  }

  @Test
  public void testRollback() throws XAException {
    Xid xid = new CustomXid(3);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.rollback(xid);
  }

  @Test
  public void testRollbackWithoutPrepare() throws XAException {
    Xid xid = new CustomXid(4);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.rollback(xid);
  }

  @Test
  public void testAutoCommit() throws Exception {
    Xid xid = new CustomXid(6);

    // When not in an XA transaction, autocommit should be true
    // per normal JDBC rules.
    assertTrue(conn.getAutoCommit());

    // When in an XA transaction, autocommit should be false
    xaRes.start(xid, XAResource.TMNOFLAGS);
    assertFalse(conn.getAutoCommit());
    xaRes.end(xid, XAResource.TMSUCCESS);
    assertFalse(conn.getAutoCommit());
    xaRes.commit(xid, true);
    assertTrue(conn.getAutoCommit());

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    assertTrue(conn.getAutoCommit());
    xaRes.commit(xid, false);
    assertTrue(conn.getAutoCommit());

    // Check that autocommit is reset to true after a 1-phase rollback
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.rollback(xid);
    assertTrue(conn.getAutoCommit());

    // Check that autocommit is reset to true after a 2-phase rollback
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.rollback(xid);
    assertTrue(conn.getAutoCommit());

    // Check that autoCommit is set correctly after a getConnection-call
    conn = xaconn.getConnection();
    assertTrue(conn.getAutoCommit());

    xaRes.start(xid, XAResource.TMNOFLAGS);

    conn.createStatement().executeQuery("SELECT * FROM testxa1");

    java.sql.Timestamp ts1 = getTransactionTimestamp(conn);

    conn.close();
    conn = xaconn.getConnection();
    assertFalse(conn.getAutoCommit());

    java.sql.Timestamp ts2 = getTransactionTimestamp(conn);

    /*
     * Check that we're still in the same transaction. close+getConnection() should not rollback the
     * XA-transaction implicitly.
     */
    assertEquals(ts1, ts2);

    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.rollback(xid);
    assertTrue(conn.getAutoCommit());
  }

  /**
   * <p>Get the time the current transaction was started from the server.</p>
   *
   * <p>This can be used to check that transaction doesn't get committed/ rolled back inadvertently, by
   * calling this once before and after the suspected piece of code, and check that they match. It's
   * a bit iffy, conceivably you might get the same timestamp anyway if the suspected piece of code
   * runs fast enough, and/or the server clock is very coarse grained. But it'll do for testing
   * purposes.</p>
   */
  private static java.sql.Timestamp getTransactionTimestamp(Connection conn) throws SQLException {
    ResultSet rs = conn.createStatement().executeQuery("SELECT now()");
    rs.next();
    return rs.getTimestamp(1);
  }

  @Test
  public void testEndThenJoin() throws XAException {
    Xid xid = new CustomXid(5);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.start(xid, XAResource.TMJOIN);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);
  }

  @Test
  public void testRestoreOfAutoCommit() throws Exception {
    conn.setAutoCommit(false);

    Xid xid = new CustomXid(14);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    assertFalse(
        "XaResource should have restored connection autocommit mode after commit or rollback to the initial state.",
        conn.getAutoCommit());

    // Test true case
    conn.setAutoCommit(true);

    xid = new CustomXid(15);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    assertTrue(
        "XaResource should have restored connection autocommit mode after commit or rollback to the initial state.",
        conn.getAutoCommit());

  }

  @Test
  public void testRestoreOfAutoCommitEndThenJoin() throws Exception {
    // Test with TMJOIN
    conn.setAutoCommit(true);

    Xid xid = new CustomXid(16);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.start(xid, XAResource.TMJOIN);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    assertTrue(
        "XaResource should have restored connection autocommit mode after start(TMNOFLAGS) end() start(TMJOIN) and then commit or rollback to the initial state.",
        conn.getAutoCommit());

  }

  /**
   * Test how the driver responds to rolling back a transaction that has already been rolled back.
   * Check the driver reports the xid does not exist. The db knows the fact. ERROR: prepared
   * transaction with identifier "blah" does not exist
   */
  @Test
  public void testRepeatedRolledBack() throws Exception {
    Xid xid = new CustomXid(654321);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    // tm crash
    xaRes.recover(XAResource.TMSTARTRSCAN);
    xaRes.rollback(xid);
    try {
      xaRes.rollback(xid);
      fail("Rollback was successful");
    } catch (XAException xae) {
      assertEquals("Checking the errorCode is XAER_NOTA indicating the " + "xid does not exist.",
          xae.errorCode, XAException.XAER_NOTA);
    }
  }

  /**
   * Invoking prepare on already prepared {@link Xid} causes {@link XAException} being thrown
   * with error code {@link XAException#XAER_PROTO}.
   */
  @Test
  public void testPreparingPreparedXid() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    try {
      xaRes.prepare(xid);
      fail("Prepare is expected to fail with XAER_PROTO as xid was already prepared");
    } catch (XAException xae) {
      assertEquals("Prepare call on already prepared xid " +  xid + " expects XAER_PROTO",
          XAException.XAER_PROTO, xae.errorCode);
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * Invoking commit on already committed {@link Xid} causes {@link XAException} being thrown
   * with error code {@link XAException#XAER_NOTA}.
   */
  @Test
  public void testCommitingCommittedXid() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.commit(xid, false);

    try {
      xaRes.commit(xid, false);
      fail("Commit is expected to fail with XAER_NOTA as xid was already committed");
    } catch (XAException xae) {
      assertEquals("Commit call on already committed xid " +  xid + " expects XAER_NOTA",
          XAException.XAER_NOTA, xae.errorCode);
    }
  }

  /**
   * Invoking commit on {@link Xid} committed by different connection.
   * That different connection could be for example transaction manager recovery.
   */
  @Test
  public void testCommitByDifferentConnection() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    XADataSource secondDs = null;
    try {
      secondDs = new PGXADataSource();
      BaseDataSourceTest.setupDataSource((PGXADataSource) secondDs);
      XAResource secondXaRes = secondDs.getXAConnection().getXAResource();
      secondXaRes.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
      secondXaRes.commit(xid, false);
    } finally {
      if (secondDs != null) {
        secondDs.getXAConnection().close();
      }
    }

    try {
      xaRes.commit(xid, false);
      fail("Commit is expected to fail with XAER_RMERR as somebody else already committed");
    } catch (XAException xae) {
      assertEquals("Commit call on already committed xid " +  xid + " expects XAER_RMERR",
          XAException.XAER_RMERR, xae.errorCode);
    }
  }

  /**
   * Invoking rollback on {@link Xid} rolled-back by different connection.
   * That different connection could be for example transaction manager recovery.
   */
  @Test
  public void testRollbackByDifferentConnection() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    XADataSource secondDs = null;
    try {
      secondDs = new PGXADataSource();
      BaseDataSourceTest.setupDataSource((PGXADataSource) secondDs);
      XAResource secondXaRes = secondDs.getXAConnection().getXAResource();
      secondXaRes.recover(XAResource.TMSTARTRSCAN | XAResource.TMENDRSCAN);
      secondXaRes.rollback(xid);
    } finally {
      if (secondDs != null) {
        secondDs.getXAConnection().close();
      }
    }

    try {
      xaRes.rollback(xid);
      fail("Rollback is expected to fail with XAER_RMERR as somebody else already rolled-back");
    } catch (XAException xae) {
      assertEquals("Rollback call on already rolled-back xid " +  xid + " expects XAER_RMERR",
          XAException.XAER_RMERR, xae.errorCode);
    }
  }

  /**
   * One-phase commit of prepared {@link Xid} should throw exception.
   */
  @Test
  public void testOnePhaseCommitOfPrepared() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    try {
      xaRes.commit(xid, true);
      fail("One-phase commit is expected to fail with XAER_PROTO when called on prepared xid");
    } catch (XAException xae) {
      assertEquals("One-phase commit of prepared xid " +  xid + " expects XAER_PROTO",
          XAException.XAER_PROTO, xae.errorCode);
    }
  }

  /**
   * Invoking one-phase commit on already one-phase committed {@link Xid} causes
   * {@link XAException} being thrown with error code {@link XAException#XAER_NOTA}.
   */
  @Test
  public void testOnePhaseCommitingCommittedXid() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    try {
      xaRes.commit(xid, true);
      fail("One-phase commit is expected to fail with XAER_NOTA as xid was already committed");
    } catch (XAException xae) {
      assertEquals("One-phase commit call on already committed xid " +  xid + " expects XAER_NOTA",
          XAException.XAER_NOTA, xae.errorCode);
    }
  }

  /**
   * When unknown xid is tried to be prepared the expected {@link XAException#errorCode}
   * is {@link XAException#XAER_NOTA}.
   */
  @Test
  public void testPrepareUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    try {
      xaRes.prepare(xid);
      fail("Prepare is expected to fail with XAER_NOTA as used unknown xid");
    } catch (XAException xae) {
      assertEquals("Prepare call on unknown xid " +  xid + " expects XAER_NOTA",
          XAException.XAER_NOTA, xae.errorCode);
    }
  }

  /**
   * When unknown xid is tried to be committed the expected {@link XAException#errorCode}
   * is {@link XAException#XAER_NOTA}.
   */
  @Test
  public void testCommitUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    Xid unknownXid = new CustomXid(42);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    try {
      xaRes.commit(unknownXid, false);
      fail("Commit is expected to fail with XAER_NOTA as used unknown xid");
    } catch (XAException xae) {
      assertEquals("Commit call on unknown xid " +  unknownXid + " expects XAER_NOTA",
          XAException.XAER_NOTA, xae.errorCode);
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * When unknown xid is tried to be committed with one-phase commit optimization
   * the expected {@link XAException#errorCode} is {@link XAException#XAER_NOTA}.
   */
  @Test
  public void testOnePhaseCommitUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    Xid unknownXid = new CustomXid(42);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    try {
      xaRes.commit(unknownXid, true);
      fail("One-phase commit is expected to fail with XAER_NOTA as used unknown xid");
    } catch (XAException xae) {
      assertEquals("Commit call on unknown xid " +  unknownXid + " expects XAER_NOTA",
          XAException.XAER_NOTA, xae.errorCode);
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * When unknown xid is tried to be rolled-back the expected {@link XAException#errorCode}
   * is {@link XAException#XAER_NOTA}.
   */
  @Test
  public void testRollbackUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    Xid unknownXid = new CustomXid(42);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    try {
      xaRes.rollback(unknownXid);
      fail("Rollback is expected to fail as used unknown xid");
    } catch (XAException xae) {
      assertEquals("Commit call on unknown xid " +  unknownXid + " expects XAER_NOTA",
          XAException.XAER_NOTA, xae.errorCode);
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * When trying to commit xid which was already removed by arbitrary action of database.
   * Resource manager can't expect state of the {@link Xid}.
   */
  @Test
  public void testDatabaseRemovesPreparedBeforeCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    clearAllPrepared();

    try {
      xaRes.commit(xid, false);
      fail("Commit is expected to fail as committed xid was removed before");
    } catch (XAException xae) {
      assertEquals("Commit call on xid " +  xid + " not known to DB expects XAER_RMERR",
          XAException.XAER_RMERR, xae.errorCode);
    }
  }

  /**
   * When trying to rollback xid which was already removed by arbitrary action of database.
   * Resource manager can't expect state of the {@link Xid}.
   */
  @Test
  public void testDatabaseRemovesPreparedBeforeRollback() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    clearAllPrepared();

    try {
      xaRes.rollback(xid);
      fail("Rollback is expected to fail as committed xid was removed before");
    } catch (XAException xae) {
      assertEquals("Rollback call on xid " +  xid + " not known to DB expects XAER_RMERR",
          XAException.XAER_RMERR, xae.errorCode);
    }
  }

  /**
   * When trying to commit and connection issue happens then
   * {@link XAException} error code {@link XAException#XAER_RMFAIL} is expected.
   */
  @Test
  public void testNetworkIssueOnCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    xaconn.close();

    try {
      xaRes.commit(xid, false);
      fail("Commit is expected to fail as connection was closed");
    } catch (XAException xae) {
      assertEquals("Commit call on closed connection expects XAER_RMFAIL",
          XAException.XAER_RMFAIL, xae.errorCode);
    }
  }

  /**
   * When trying to one-phase commit and connection issue happens then
   * {@link XAException} error code {@link XAException#XAER_RMFAIL} is expected.
   */
  @Test
  public void testNetworkIssueOnOnePhaseCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);

    xaconn.close();

    try {
      xaRes.commit(xid, true);
      fail("One-phase commit is expected to fail as connection was closed");
    } catch (XAException xae) {
      assertEquals("One-phase commit call on closed connection expects XAER_RMFAIL",
          XAException.XAER_RMFAIL, xae.errorCode);
    }
  }

  /**
   * When trying to rollback and connection issue happens then
   * {@link XAException} error code {@link XAException#XAER_RMFAIL} is expected.
   */
  @Test
  public void testNetworkIssueOnRollback() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    xaconn.close();

    try {
      xaRes.rollback(xid);
      fail("Rollback is expected to fail as connection was closed");
    } catch (XAException xae) {
      assertEquals("Rollback call on closed connection expects XAER_RMFAIL",
          XAException.XAER_RMFAIL, xae.errorCode);
    }
  }

  /**
   * When using deferred constraints a contraint violation can occur on prepare. This has to be
   * mapped to the correct XA Error Code
   */
  @Test
  public void testMappingOfConstraintViolations() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    assertEquals(0, conn.createStatement().executeUpdate("SET CONSTRAINTS ALL DEFERRED"));
    assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (4)"));
    xaRes.end(xid, XAResource.TMSUCCESS);

    try {
      xaRes.prepare(xid);

      fail("Prepare is expected to fail as an integrity violation occurred");
    } catch (XAException xae) {
      assertEquals("Prepare call with deferred constraints violations expects XA_RBINTEGRITY",
          XAException.XA_RBINTEGRITY, xae.errorCode);
    }
  }

  /*
   * We don't support transaction interleaving. public void testInterleaving1() throws Exception {
   * Xid xid1 = new CustomXid(1); Xid xid2 = new CustomXid(2);
   *
   * xaRes.start(xid1, XAResource.TMNOFLAGS); conn.createStatement().executeUpdate(
   * "UPDATE testxa1 SET foo = 'ccc'"); xaRes.end(xid1, XAResource.TMSUCCESS);
   *
   * xaRes.start(xid2, XAResource.TMNOFLAGS); conn.createStatement().executeUpdate(
   * "UPDATE testxa2 SET foo = 'bbb'");
   *
   * xaRes.commit(xid1, true);
   *
   * xaRes.end(xid2, XAResource.TMSUCCESS);
   *
   * xaRes.commit(xid2, true);
   *
   * } public void testInterleaving2() throws Exception { Xid xid1 = new CustomXid(1); Xid xid2 =
   * new CustomXid(2); Xid xid3 = new CustomXid(3);
   *
   * xaRes.start(xid1, XAResource.TMNOFLAGS); conn.createStatement().executeUpdate(
   * "UPDATE testxa1 SET foo = 'aa'"); xaRes.end(xid1, XAResource.TMSUCCESS);
   *
   * xaRes.start(xid2, XAResource.TMNOFLAGS); conn.createStatement().executeUpdate(
   * "UPDATE testxa2 SET foo = 'bb'"); xaRes.end(xid2, XAResource.TMSUCCESS);
   *
   * xaRes.start(xid3, XAResource.TMNOFLAGS); conn.createStatement().executeUpdate(
   * "UPDATE testxa3 SET foo = 'cc'"); xaRes.end(xid3, XAResource.TMSUCCESS);
   *
   * xaRes.commit(xid1, true); xaRes.commit(xid2, true); xaRes.commit(xid3, true); }
   */
}
