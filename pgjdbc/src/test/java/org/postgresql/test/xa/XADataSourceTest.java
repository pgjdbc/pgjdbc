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

import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.tags.Xa;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.util.PSQLException;
import org.postgresql.xa.PGXADataSource;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
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
      TestUtil.createTable(con, "testxa1", "foo int");
      TestUtil.createTable(con, "testxa2", "foo int primary key");
      TestUtil.createTable(con, "testxa3", "foo int references testxa2(foo) deferrable");
    }
  }

  @AfterAll
  static void afterClass() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "testxa3");
      TestUtil.dropTable(con, "testxa2");
      TestUtil.dropTable(con, "testxa1");
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

    TestUtil.execute(dbConn, "TRUNCATE testxa3 CASCADE");
    TestUtil.execute(dbConn, "TRUNCATE testxa2 CASCADE");
    TestUtil.execute(dbConn, "TRUNCATE testxa1 CASCADE");

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

    // XAResource methods leave the JDBC autoCommit flag invariant (see xaMethods_doNotChangeAutoCommit
    // for the full per-method check). Spot-check the one-phase and two-phase paths here.
    xaRes.start(xid, XAResource.TMNOFLAGS);
    assertTrue(conn.getAutoCommit(), "start() must not change autoCommit");
    xaRes.end(xid, XAResource.TMSUCCESS);
    assertTrue(conn.getAutoCommit(), "end() must not change autoCommit");
    xaRes.commit(xid, true);
    assertTrue(conn.getAutoCommit(), "one-phase commit() must not change autoCommit");

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    assertTrue(conn.getAutoCommit(), "prepare() must not change autoCommit");
    xaRes.commit(xid, false);
    assertTrue(conn.getAutoCommit(), "two-phase commit() must not change autoCommit");

    // Same for a 1-phase rollback
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.rollback(xid);
    assertTrue(conn.getAutoCommit(), "1-phase rollback() must not change autoCommit");

    // Same for a 2-phase rollback
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.rollback(xid);
    assertTrue(conn.getAutoCommit(), "2-phase rollback() must not change autoCommit");

    // close()+getConnection() during an active XA branch returns to the same server transaction
    conn = xaconn.getConnection();
    assertTrue(conn.getAutoCommit());

    xaRes.start(xid, XAResource.TMNOFLAGS);

    conn.createStatement().executeQuery("SELECT * FROM testxa1");

    Timestamp ts1 = getTransactionTimestamp(conn);

    conn.close();
    conn = xaconn.getConnection();
    // state != IDLE on getConnection(), so the JDBC handle is not reset to autoCommit=true here.
    // The physical connection's autoCommit is whatever it was before start() (true in this test).
    assertTrue(conn.getAutoCommit());

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

  private TransactionState transactionState(Connection c) throws SQLException {
    return c.unwrap(BaseConnection.class).getTransactionState();
  }

  /**
   * recover() on a connection with autoCommit=false must not leave the connection in OPEN: the
   * SELECT against pg_prepared_xacts uses QUERY_SUPPRESS_BEGIN, so pgjdbc does not prepend a BEGIN.
   * A follow-up commit(xid, false) on the recovered xid then succeeds, instead of failing the
   * "2nd phase commit must be issued using an idle connection" precondition.
   */
  @Test
  void recover_withAutoCommitFalse_doesNotOpenTransaction() throws Exception {
    Xid xid = new CustomXid(0xa1000001);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    // Simulate the managed-datasource scenario: the recovery flow lands on a connection that the
    // pool has put into autoCommit=false.
    conn.setAutoCommit(false);
    assertEquals(TransactionState.IDLE, transactionState(conn),
        "autoCommit=false alone must not start a transaction");

    Xid[] recovered = xaRes.recover(XAResource.TMSTARTRSCAN);
    assertTrue(Arrays.asList(recovered).contains(xid), "Did not recover prepared xid");
    assertEquals(TransactionState.IDLE, transactionState(conn),
        "recover() must leave transactionState=IDLE on an autoCommit=false connection");

    // Same XAResource, same xid → 2nd phase commit must succeed.
    xaRes.commit(xid, false);
    assertEquals(TransactionState.IDLE, transactionState(conn));
  }

  /**
   * recover() called on a connection that already has an open local transaction must not commit
   * or roll back that transaction. The SELECT against pg_prepared_xacts runs inside the caller's
   * transaction with QUERY_SUPPRESS_BEGIN; transactionState ends where it started.
   */
  @Test
  void recover_withUserTransactionInFlight_doesNotCommitUserWork() throws Exception {
    // First, prepare a transaction so recover() has something to return.
    Xid prepared = new CustomXid(0xa1000002);
    xaRes.start(prepared, XAResource.TMNOFLAGS);
    conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (2)");
    xaRes.end(prepared, XAResource.TMSUCCESS);
    xaRes.prepare(prepared);

    // Now open a local transaction on the same physical connection with an unrelated INSERT.
    conn.setAutoCommit(false);
    conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (99)");
    assertEquals(TransactionState.OPEN, transactionState(conn));

    // recover() must see the prepared xid and leave the local transaction OPEN.
    Xid[] recovered = xaRes.recover(XAResource.TMSTARTRSCAN);
    assertTrue(Arrays.asList(recovered).contains(prepared), "Did not recover prepared xid");
    assertEquals(TransactionState.OPEN, transactionState(conn),
        "recover() must not change the caller's transactionState");

    // Roll back the local transaction. The unrelated INSERT must be gone.
    conn.rollback();
    try (ResultSet rs = dbConn.createStatement().executeQuery("SELECT count(*) FROM testxa1 WHERE foo = 99")) {
      rs.next();
      assertEquals(0, rs.getInt(1), "recover() must not have committed the caller's INSERT");
    }

    // Clean up the prepared transaction.
    conn.setAutoCommit(true);
    xaRes.rollback(prepared);
  }

  /**
   * When the caller's local transaction is already in FAILED state, recover() cannot read
   * pg_prepared_xacts (PG rejects the SELECT with "current transaction is aborted"). The driver
   * surfaces this as XAException(XAER_RMERR); the caller's transaction is left untouched.
   */
  @Test
  void recover_inFailedTransaction_failsWithRMERR() throws Exception {
    conn.setAutoCommit(false);
    // Force the connection into TransactionState.FAILED by running a query that errors inside the
    // caller's transaction.
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("SELECT 1 FROM no_such_table_for_xa_test");
      fail("Expected SQL error to put transaction into FAILED");
    } catch (SQLException expected) {
      // ignore
    }
    assertEquals(TransactionState.FAILED, transactionState(conn));

    try {
      xaRes.recover(XAResource.TMSTARTRSCAN);
      fail("recover() must fail on a FAILED transaction");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMERR, xae.errorCode,
          "recover() on a FAILED transaction expects XAER_RMERR");
    }
    assertEquals(TransactionState.FAILED, transactionState(conn),
        "recover() must not silently reset the caller's transaction");

    // Clean up so the @AfterEach connection close does not complain.
    conn.rollback();
    conn.setAutoCommit(true);
  }

  /**
   * commit(xid, false) on a connection where the caller has left a local transaction open must
   * fail with XAER_RMFAIL, not silently commit the caller's work. XAER_RMFAIL signals the
   * transaction manager to retry on a fresh XAResource.
   */
  @Test
  void commitPrepared_failsCleanlyOnDirtyConnection() throws Exception {
    // Prepare a transaction on a separate XAConnection so we can attempt the 2-phase commit on a
    // connection that is also holding a local transaction.
    Xid prepared = new CustomXid(0xa1000003);
    XAConnection xaconn2 = xaDs.getXAConnection();
    try {
      XAResource xaRes2 = xaconn2.getXAResource();
      Connection conn2 = xaconn2.getConnection();
      xaRes2.start(prepared, XAResource.TMNOFLAGS);
      conn2.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (3)");
      xaRes2.end(prepared, XAResource.TMSUCCESS);
      xaRes2.prepare(prepared);
    } finally {
      xaconn2.close();
    }

    // Open a local transaction on the recovery connection.
    conn.setAutoCommit(false);
    conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (88)");
    assertEquals(TransactionState.OPEN, transactionState(conn));

    try {
      xaRes.commit(prepared, false);
      fail("commit(prepared, false) must fail on a connection with an open local transaction");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMFAIL, xae.errorCode,
          "commit(prepared, false) on a dirty connection expects XAER_RMFAIL");
    }
    assertEquals(TransactionState.OPEN, transactionState(conn),
        "commit(prepared, false) must not touch the caller's transaction");

    // Clean up.
    conn.rollback();
    conn.setAutoCommit(true);
    xaRes.rollback(prepared);
  }

  /**
   * Symmetric to {@link #commitPrepared_failsCleanlyOnDirtyConnection()}: rollback(xid) of a
   * prepared transaction on a connection with an open local transaction must fail with
   * XAER_RMFAIL, not silently roll back the caller's work.
   */
  @Test
  void rollbackPrepared_failsCleanlyOnDirtyConnection() throws Exception {
    Xid prepared = new CustomXid(0xa1000004);
    XAConnection xaconn2 = xaDs.getXAConnection();
    try {
      XAResource xaRes2 = xaconn2.getXAResource();
      Connection conn2 = xaconn2.getConnection();
      xaRes2.start(prepared, XAResource.TMNOFLAGS);
      conn2.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (4)");
      xaRes2.end(prepared, XAResource.TMSUCCESS);
      xaRes2.prepare(prepared);
    } finally {
      xaconn2.close();
    }

    conn.setAutoCommit(false);
    conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (77)");
    assertEquals(TransactionState.OPEN, transactionState(conn));

    try {
      xaRes.rollback(prepared);
      fail("rollback(prepared) must fail on a connection with an open local transaction");
    } catch (XAException xae) {
      assertEquals(XAException.XAER_RMFAIL, xae.errorCode,
          "rollback(prepared) on a dirty connection expects XAER_RMFAIL");
    }
    assertEquals(TransactionState.OPEN, transactionState(conn),
        "rollback(prepared) must not touch the caller's transaction");

    // Clean up.
    conn.rollback();
    conn.setAutoCommit(true);
    xaRes.rollback(prepared);
  }

  /**
   * XAResource methods must not change the caller's JDBC autoCommit flag on either the success or
   * the failure path. Verified on every method in the lifecycle.
   */
  @Test
  void xaMethods_doNotChangeAutoCommit() throws Exception {
    for (boolean initial : new boolean[]{true, false}) {
      // Reset the connection to a known state before each iteration.
      if (!conn.getAutoCommit()) {
        conn.rollback();
      }
      conn.setAutoCommit(initial);
      assertEquals(initial, conn.getAutoCommit(), "precondition for initial=" + initial);

      Xid xid = new CustomXid(0xa1000010 + (initial ? 1 : 0));

      xaRes.start(xid, XAResource.TMNOFLAGS);
      assertEquals(initial, conn.getAutoCommit(), "start() must not change autoCommit");

      conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (10)");
      assertEquals(initial, conn.getAutoCommit(), "user SQL must not change autoCommit");

      xaRes.end(xid, XAResource.TMSUCCESS);
      assertEquals(initial, conn.getAutoCommit(), "end() must not change autoCommit");

      xaRes.prepare(xid);
      assertEquals(initial, conn.getAutoCommit(), "prepare() must not change autoCommit");

      xaRes.commit(xid, false);
      assertEquals(initial, conn.getAutoCommit(), "2-phase commit() must not change autoCommit");

      Xid[] recovered = xaRes.recover(XAResource.TMSTARTRSCAN);
      Arrays.toString(recovered); // silence unused warnings; the call is the point
      assertEquals(initial, conn.getAutoCommit(), "recover() must not change autoCommit");
    }

    // Also check the failure path: a forced PREPARE TRANSACTION failure via a deferred FK
    // violation must leave autoCommit untouched.
    Xid xid = new CustomXid(0xa1000020);
    conn.setAutoCommit(true);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeUpdate("SET CONSTRAINTS ALL DEFERRED");
    conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (404)");
    xaRes.end(xid, XAResource.TMSUCCESS);
    try {
      xaRes.prepare(xid);
      fail("prepare() with a deferred constraint violation must fail");
    } catch (XAException expected) {
      // ignore
    }
    assertTrue(conn.getAutoCommit(),
        "prepare() must not change autoCommit even when PREPARE TRANSACTION fails");

    xaRes.rollback(xid);
    assertTrue(conn.getAutoCommit());
  }

  /**
   * When PREPARE TRANSACTION fails (here, on a deferred foreign-key constraint), the driver must leave the XA
   * branch in a state where the transaction manager can recover it by calling rollback(xid).
   * That means {@code state == ENDED} with {@code currentXid == xid}, so rollback(xid) takes the
   * active-branch path and issues a plain ROLLBACK — not the prepared-branch path that would
   * issue ROLLBACK PREPARED against a non-existent gid.
   *
   * <p>Reproduces the scenario from
   * <a href="https://github.com/pgjdbc/pgjdbc/issues/3123">Issue #3123</a> (Narayana escalating
   * a failed prepare to {@code HeuristicMixedException}) and
   * <a href="https://github.com/pgjdbc/pgjdbc/issues/3153">Issue #3153</a> (the pgjdbc-internal
   * diagnosis: rollback() should not return XAER_RMERR after a failed prepare).</p>
   */
  @Test
  void prepareFailure_leavesBranchRollbackable() throws Exception {
    // Use a deferred FK constraint violation to force PREPARE TRANSACTION to fail. The INSERT
    // succeeds inside the branch, end() succeeds, but PREPARE evaluates the deferred constraint
    // and rejects the commit.
    Xid xid = new CustomXid(0xa1000030);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeUpdate("SET CONSTRAINTS ALL DEFERRED");
    conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (777)");
    xaRes.end(xid, XAResource.TMSUCCESS);
    try {
      xaRes.prepare(xid);
      fail("PREPARE TRANSACTION with a deferred constraint violation must fail");
    } catch (XAException expected) {
      // ignore
    }

    // The driver must still let us roll back the active branch. The successful rollback issues
    // ROLLBACK (active-branch path) and clears the INSERT from the server transaction. If state
    // had been mutated to IDLE before SQL — as the pre-fix code did — rollback(xid) would have
    // taken the prepared-branch path and tried ROLLBACK PREPARED against a gid the server has
    // never seen.
    xaRes.rollback(xid);

    try (ResultSet rs = dbConn.createStatement().executeQuery("SELECT count(*) FROM testxa3 WHERE foo = 777")) {
      rs.next();
      assertEquals(0, rs.getInt(1), "rollback() after a failed prepare() must roll back the active branch");
    }
  }

  /**
   * The ConnectionHandler proxy must reject both setAutoCommit(true) and setAutoCommit(false)
   * while an XA branch is active on the connection, per JTA 1.2 §3.4. The previous behaviour
   * blocked only setAutoCommit(true).
   */
  @Test
  void connectionHandler_rejectsBothAutoCommitDirections() throws Exception {
    Xid xid = new CustomXid(0xa1000040);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    try {
      try {
        conn.setAutoCommit(true);
        fail("setAutoCommit(true) must be rejected during an active XA branch");
      } catch (PSQLException expected) {
        // ignore
      }
      try {
        conn.setAutoCommit(false);
        fail("setAutoCommit(false) must be rejected during an active XA branch");
      } catch (PSQLException expected) {
        // ignore
      }
    } finally {
      xaRes.end(xid, XAResource.TMSUCCESS);
      xaRes.rollback(xid);
    }
  }

  /**
   * The ConnectionHandler must also reject {@code setSavepoint()} and {@code setSavepoint(name)}
   * while an XA branch is active, per JTA 1.2 §3.4. Until this fix the guard misspelled the
   * method name as {@code setSavePoint}, so savepoints silently went through to the underlying
   * connection.
   */
  @Test
  void connectionHandler_rejectsSetSavepoint() throws Exception {
    Xid xid = new CustomXid(0xa1000041);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    try {
      try {
        conn.setSavepoint();
        fail("setSavepoint() must be rejected during an active XA branch");
      } catch (PSQLException expected) {
        // ignore
      }
      try {
        conn.setSavepoint("xa_sp");
        fail("setSavepoint(name) must be rejected during an active XA branch");
      } catch (PSQLException expected) {
        // ignore
      }
    } finally {
      xaRes.end(xid, XAResource.TMSUCCESS);
      xaRes.rollback(xid);
    }
  }
}
