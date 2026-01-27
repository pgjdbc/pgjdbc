/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.xa;

import static javax.transaction.xa.XAException.XA_RDONLY;
import static javax.transaction.xa.XAResource.XA_OK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.tags.Xa;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.util.PSQLException;
import org.postgresql.xa.PGXADataSource;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.Random;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

@Xa
public class XADataSourceTest {

  private XADataSource xaDs;

  private Connection dbConn;
  private boolean connIsSuper;

  private XAConnection xaconn;
  private XAResource xaRes;
  private Connection conn;

  public XADataSourceTest() throws PSQLException {
    xaDs = new PGXADataSource();
    BaseDataSourceTest.setupDataSource((PGXADataSource) xaDs);
  }

  @BeforeAll
  static void beforeClass() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      assumeTrue(isPreparedTransactionEnabled(con), "max_prepared_transactions should be non-zero for XA tests");
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    dbConn = TestUtil.openDB();

    // Check if we're operating as a superuser; some tests require it.
    Statement st = dbConn.createStatement();
    st.executeQuery("SHOW is_superuser;");
    ResultSet rs = st.getResultSet();
    rs.next(); // One row is guaranteed
    connIsSuper = rs.getBoolean(1); // One col is guaranteed
    st.close();

    TestUtil.createTable(dbConn, "testxa1", "foo int");
    TestUtil.createTable(dbConn, "testxa2", "foo int primary key");
    TestUtil.createTable(dbConn, "testxa3", "foo int references testxa2(foo) deferrable");

    clearAllPrepared();

    xaconn = xaDs.getXAConnection();
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

  @AfterEach
  void tearDown() throws SQLException, XAException {
    try {
      xaconn.close();
    } catch (Exception ignored) {
    }

    clearAllPrepared();
    TestUtil.dropTable(dbConn, "testxa3");
    TestUtil.dropTable(dbConn, "testxa2");
    TestUtil.dropTable(dbConn, "testxa1");
    TestUtil.closeDB(dbConn);

  }

  private void clearAllPrepared() throws SQLException, XAException {
    XAConnection con = xaDs.getXAConnection();
    XAResource xaResource = con.getXAResource();
    try {
      // Get the first batch of the xids
      Xid[] xids = xaResource.recover(XAResource.TMSTARTRSCAN);
      while (xids.length != 0) {
        for (Xid xid : xids) {
          xaResource.rollback(xid);
        }
        // Get the next batch of the xids
        xids = xaResource.recover(XAResource.TMNOFLAGS);
      }
    } finally {
      xaResource.recover(XAResource.TMENDRSCAN);
      con.close();
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
    public boolean equals(@Nullable Object o) {
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
      return Arrays.equals(other.getGlobalTransactionId(), this.getGlobalTransactionId());
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
  void wrapperEquals() throws Exception {
    assertEquals(conn, conn, "Wrappers should be equal");
    assertNotEquals(null, conn, "Wrapper should be unequal to null");
    assertNotEquals("dummy string object", conn, "Wrapper should be unequal to unrelated object");
  }

  @Test
  void onePhase() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);
  }

  @Test
  void twoPhaseCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    assertEquals(XA_OK, xaRes.prepare(xid));
    xaRes.commit(xid, false);
  }

  @Test
  void twoPhasePrepareReadOnly() throws Exception {
    Xid xid = new CustomXid(1);
    conn.setReadOnly(true);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    assertEquals(XA_RDONLY, xaRes.prepare(xid));
    xaRes.commit(xid, false);
  }

  @Test
  void closeBeforeCommit() throws Exception {
    Xid xid = new CustomXid(5);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
    conn.close();
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    ResultSet rs = dbConn.createStatement().executeQuery("SELECT foo FROM testxa1");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
  }

  @Test
  void recover() throws Exception {
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

      assertTrue(recoveredXid, "Did not recover prepared xid");
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

      assertFalse(recoveredXid, "Recovered rolled back xid");
    }
  }

  @Test
  void rollback() throws XAException {
    Xid xid = new CustomXid(3);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.rollback(xid);
  }

  @Test
  void rollbackWithoutPrepare() throws XAException {
    Xid xid = new CustomXid(4);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.rollback(xid);
  }

  @Test
  void autoCommit() throws Exception {
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

    Timestamp ts1 = getTransactionTimestamp(conn);

    conn.close();
    conn = xaconn.getConnection();
    assertFalse(conn.getAutoCommit());

    Timestamp ts2 = getTransactionTimestamp(conn);

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
   * Get the time the current transaction was started from the server.
   *
   * <p>This can be used to check that transaction doesn't get committed/ rolled back inadvertently, by
   * calling this once before and after the suspected piece of code, and check that they match. It's
   * a bit iffy, conceivably you might get the same timestamp anyway if the suspected piece of code
   * runs fast enough, and/or the server clock is very coarse grained. But it'll do for testing
   * purposes.</p>
   */
  private static Timestamp getTransactionTimestamp(Connection conn) throws SQLException {
    ResultSet rs = conn.createStatement().executeQuery("SELECT now()");
    rs.next();
    return rs.getTimestamp(1);
  }

  @Test
  void endThenJoin() throws XAException {
    Xid xid = new CustomXid(5);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.start(xid, XAResource.TMJOIN);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);
  }

  @Test
  void restoreOfAutoCommit() throws Exception {
    conn.setAutoCommit(false);

    Xid xid = new CustomXid(14);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    assertFalse(
        conn.getAutoCommit(),
        "XaResource should have restored connection autocommit mode after commit or rollback to the initial state.");

    // Test true case
    conn.setAutoCommit(true);

    xid = new CustomXid(15);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    assertTrue(
        conn.getAutoCommit(),
        "XaResource should have restored connection autocommit mode after commit or rollback to the initial state.");

  }

  @Test
  void restoreOfAutoCommitEndThenJoin() throws Exception {
    // Test with TMJOIN
    conn.setAutoCommit(true);

    Xid xid = new CustomXid(16);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.start(xid, XAResource.TMJOIN);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    assertTrue(
        conn.getAutoCommit(),
        "XaResource should have restored connection autocommit mode after start(TMNOFLAGS) end() start(TMJOIN) and then commit or rollback to the initial state.");

  }

  /**
   * Test how the driver responds to rolling back a transaction that has already been rolled back.
   * Check the driver reports the xid does not exist. The db knows the fact. ERROR: prepared
   * transaction with identifier "blah" does not exist
   */
  @Test
  void repeatedRolledBack() throws Exception {
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
      assertEquals(XAException.XAER_NOTA, xae.errorCode, "Checking the errorCode is XAER_NOTA indicating the " + "xid does not exist.");
    }
  }

  /**
   * Invoking prepare on already prepared {@link Xid} causes {@link XAException} being thrown
   * with error code {@link XAException#XAER_PROTO}.
   */
  @Test
  void preparingPreparedXid() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    try {
      xaRes.prepare(xid);
      fail("Prepare is expected to fail with XAER_PROTO as xid was already prepared");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_PROTO, xae.errorCode, "Prepare call on already prepared xid " + xid + " expects XAER_PROTO");
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * Invoking commit on already committed {@link Xid} causes {@link XAException} being thrown
   * with error code {@link XAException#XAER_NOTA}.
   */
  @Test
  void committingCommittedXid() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.commit(xid, false);

    try {
      xaRes.commit(xid, false);
      fail("Commit is expected to fail with XAER_NOTA as xid was already committed");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_NOTA, xae.errorCode, "Commit call on already committed xid " + xid + " expects XAER_NOTA");
    }
  }

  /**
   * Invoking commit on {@link Xid} committed by different connection.
   * That different connection could be for example transaction manager recovery.
   */
  @Test
  void commitByDifferentConnection() throws Exception {
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
      assertEquals(XAException.XAER_RMERR, xae.errorCode, "Commit call on already committed xid " + xid + " expects XAER_RMERR");
    }
  }

  /**
   * Invoking rollback on {@link Xid} rolled-back by different connection.
   * That different connection could be for example transaction manager recovery.
   */
  @Test
  void rollbackByDifferentConnection() throws Exception {
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
      assertEquals(XAException.XAER_RMERR, xae.errorCode, "Rollback call on already rolled-back xid " + xid + " expects XAER_RMERR");
    }
  }

  /**
   * One-phase commit of prepared {@link Xid} should throw exception.
   */
  @Test
  void onePhaseCommitOfPrepared() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    try {
      xaRes.commit(xid, true);
      fail("One-phase commit is expected to fail with XAER_PROTO when called on prepared xid");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_PROTO, xae.errorCode, "One-phase commit of prepared xid " + xid + " expects XAER_PROTO");
    }
  }

  /**
   * Invoking one-phase commit on already one-phase committed {@link Xid} causes
   * {@link XAException} being thrown with error code {@link XAException#XAER_NOTA}.
   */
  @Test
  void onePhaseCommittingCommittedXid() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);

    try {
      xaRes.commit(xid, true);
      fail("One-phase commit is expected to fail with XAER_NOTA as xid was already committed");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_NOTA, xae.errorCode, "One-phase commit call on already committed xid " + xid + " expects XAER_NOTA");
    }
  }

  /**
   * When unknown xid is tried to be prepared the expected {@link XAException#errorCode}
   * is {@link XAException#XAER_NOTA}.
   */
  @Test
  void prepareUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    try {
      xaRes.prepare(xid);
      fail("Prepare is expected to fail with XAER_NOTA as used unknown xid");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_NOTA, xae.errorCode, "Prepare call on unknown xid " + xid + " expects XAER_NOTA");
    }
  }

  /**
   * When unknown xid is tried to be committed the expected {@link XAException#errorCode}
   * is {@link XAException#XAER_NOTA}.
   */
  @Test
  void commitUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    Xid unknownXid = new CustomXid(42);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    try {
      xaRes.commit(unknownXid, false);
      fail("Commit is expected to fail with XAER_NOTA as used unknown xid");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_NOTA, xae.errorCode, "Commit call on unknown xid " + unknownXid + " expects XAER_NOTA");
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * When unknown xid is tried to be committed with one-phase commit optimization
   * the expected {@link XAException#errorCode} is {@link XAException#XAER_NOTA}.
   */
  @Test
  void onePhaseCommitUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    Xid unknownXid = new CustomXid(42);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    try {
      xaRes.commit(unknownXid, true);
      fail("One-phase commit is expected to fail with XAER_NOTA as used unknown xid");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_NOTA, xae.errorCode, "Commit call on unknown xid " + unknownXid + " expects XAER_NOTA");
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * When unknown xid is tried to be rolled-back the expected {@link XAException#errorCode}
   * is {@link XAException#XAER_NOTA}.
   */
  @Test
  void rollbackUnknownXid() throws Exception {
    Xid xid = new CustomXid(1);
    Xid unknownXid = new CustomXid(42);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    try {
      xaRes.rollback(unknownXid);
      fail("Rollback is expected to fail as used unknown xid");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_NOTA, xae.errorCode, "Commit call on unknown xid " + unknownXid + " expects XAER_NOTA");
    } finally {
      xaRes.rollback(xid);
    }
  }

  /**
   * When trying to commit xid which was already removed by arbitrary action of database.
   * Resource manager can't expect state of the {@link Xid}.
   */
  @Test
  void databaseRemovesPreparedBeforeCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    clearAllPrepared();

    try {
      xaRes.commit(xid, false);
      fail("Commit is expected to fail as committed xid was removed before");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMERR, xae.errorCode, "Commit call on xid " + xid + " not known to DB expects XAER_RMERR");
    }
  }

  /**
   * When trying to rollback xid which was already removed by arbitrary action of database.
   * Resource manager can't expect state of the {@link Xid}.
   */
  @Test
  void databaseRemovesPreparedBeforeRollback() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    clearAllPrepared();

    try {
      xaRes.rollback(xid);
      fail("Rollback is expected to fail as committed xid was removed before");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMERR, xae.errorCode, "Rollback call on xid " + xid + " not known to DB expects XAER_RMERR");
    }
  }

  /**
   * When trying to commit and connection issue happens then
   * {@link XAException} error code {@link XAException#XAER_RMFAIL} is expected.
   */
  @Test
  void networkIssueOnCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    xaconn.close();

    try {
      xaRes.commit(xid, false);
      fail("Commit is expected to fail as connection was closed");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMFAIL, xae.errorCode, "Commit call on closed connection expects XAER_RMFAIL");
    }
  }

  /**
   * When trying to one-phase commit and connection issue happens then
   * {@link XAException} error code {@link XAException#XAER_RMFAIL} is expected.
   */
  @Test
  void networkIssueOnOnePhaseCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);

    xaconn.close();

    try {
      xaRes.commit(xid, true);
      fail("One-phase commit is expected to fail as connection was closed");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMFAIL, xae.errorCode, "One-phase commit call on closed connection expects XAER_RMFAIL");
    }
  }

  /**
   * When trying to rollback and connection issue happens then
   * {@link XAException} error code {@link XAException#XAER_RMFAIL} is expected.
   */
  @Test
  void networkIssueOnRollback() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    xaconn.close();

    try {
      xaRes.rollback(xid);
      fail("Rollback is expected to fail as connection was closed");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMFAIL, xae.errorCode, "Rollback call on closed connection expects XAER_RMFAIL");
    }
  }

  /**
   * When using deferred constraints a constraint violation can occur on prepare. This has to be
   * mapped to the correct XA Error Code
   */
  @Test
  void mappingOfConstraintViolations() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    assertEquals(0, conn.createStatement().executeUpdate("SET CONSTRAINTS ALL DEFERRED"));
    assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (4)"));
    xaRes.end(xid, XAResource.TMSUCCESS);

    try {
      xaRes.prepare(xid);

      fail("Prepare is expected to fail as an integrity violation occurred");
    } catch (XAException xae) {
      assertEquals(XAException.XA_RBINTEGRITY, xae.errorCode, "Prepare call with deferred constraints violations expects XA_RBINTEGRITY");
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
