/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2014, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.xa;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.xa.PGXADataSource;

import junit.framework.TestCase;

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

public class XADataSourceTest extends TestCase {

  private XADataSource _ds;

  private Connection _conn;
  private boolean connIsSuper;

  private XAConnection xaconn;
  private XAResource xaRes;
  private Connection conn;


  public XADataSourceTest(String name) {
    super(name);

    _ds = new PGXADataSource();
    BaseDataSourceTest.setupDataSource((PGXADataSource) _ds);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();

    // Check if we're operating as a superuser; some tests require it.
    Statement st = _conn.createStatement();
    st.executeQuery("SHOW is_superuser;");
    ResultSet rs = st.getResultSet();
    rs.next(); // One row is guaranteed
    connIsSuper = rs.getBoolean(1); // One col is guaranteed
    st.close();

    TestUtil.createTable(_conn, "testxa1", "foo int");

    clearAllPrepared();

    xaconn = _ds.getXAConnection();
    xaRes = xaconn.getXAResource();
    conn = xaconn.getConnection();
  }

  protected void tearDown() throws SQLException {
    xaconn.close();
    clearAllPrepared();

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

    public int getFormatId() {
      return 0;
    }


    public byte[] getGlobalTransactionId() {
      return gtrid;
    }

    public byte[] getBranchQualifier() {
      return bqual;
    }

    public boolean equals(Object o) {
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
  }

  /*
   * Check that the equals method works for the connection wrapper returned
   * by PGXAConnection.getConnection().
   */
  public void testWrapperEquals() throws Exception {
    assertTrue("Wrappers should be equal", conn.equals(conn));
  }

  public void testOnePhase() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);
  }

  public void testTwoPhaseCommit() throws Exception {
    Xid xid = new CustomXid(1);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.commit(xid, false);
  }

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

  public void testRecover() throws Exception {
    Xid xid = new CustomXid(12345);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    conn.createStatement().executeQuery("SELECT * FROM testxa1");
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);

    {
      Xid[] recoveredXidArray = xaRes.recover(XAResource.TMSTARTRSCAN);

      boolean recoveredXid = false;

      for (int i = 0; i < recoveredXidArray.length; i++) {
        if (xid.equals(recoveredXidArray[i])) {
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

      for (int c = 0; c < recoveredXidArray.length; c++) {
        if (xaRes.equals(recoveredXidArray[c])) {
          recoveredXid = true;
          break;
        }
      }

      assertFalse("Recovered rolled back xid", recoveredXid);
    }
  }

  public void testRollback() throws XAException {
    Xid xid = new CustomXid(3);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.rollback(xid);
  }

  public void testRollbackWithoutPrepare() throws XAException {
    Xid xid = new CustomXid(4);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.rollback(xid);
  }

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

        /* Check that we're still in the same transaction.
         * close+getConnection() should not rollback the XA-transaction
         * implicitly.
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
   * This can be used to check that transaction doesn't get committed/ rolled back inadvertently, by
   * calling this once before and after the suspected piece of code, and check that they match. It's
   * a bit iffy, conceivably you might get the same timestamp anyway if the suspected piece of code
   * runs fast enough, and/or the server clock is very coarse grained. But it'll do for testing
   * purposes.
   */
  private static java.sql.Timestamp getTransactionTimestamp(Connection conn) throws SQLException {
    ResultSet rs = conn.createStatement().executeQuery("SELECT now()");
    rs.next();
    return rs.getTimestamp(1);
  }

  public void testEndThenJoin() throws XAException {
    Xid xid = new CustomXid(5);

    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.start(xid, XAResource.TMJOIN);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.commit(xid, true);
  }

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
   * Check the driver reports the xid does not exist. The db knows the fact. ERROR:  prepared
   * transaction with identifier "blah" does not exist
   */
  public void testRepeatedRolledBack() throws Exception {
    Xid xid = new CustomXid(654321);
    xaRes.start(xid, XAResource.TMNOFLAGS);
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    //tm crash
    xaRes.recover(XAResource.TMSTARTRSCAN);
    xaRes.rollback(xid);
    try {
      xaRes.rollback(xid);
      fail("Rollback was successful");
    } catch (XAException xae) {
      assertEquals("Checking the errorCode is XAER_NOTA indicating the "
          + "xid does not exist.", XAException.XAER_NOTA, xae.errorCode);
    }
  }

    /* We don't support transaction interleaving.
    public void testInterleaving1() throws Exception {
     Xid xid1 = new CustomXid(1);
     Xid xid2 = new CustomXid(2);

     xaRes.start(xid1, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = 'ccc'");
     xaRes.end(xid1, XAResource.TMSUCCESS);

     xaRes.start(xid2, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa2 SET foo = 'bbb'");

     xaRes.commit(xid1, true);

     xaRes.end(xid2, XAResource.TMSUCCESS);

     xaRes.commit(xid2, true);

    }
    public void testInterleaving2() throws Exception {
     Xid xid1 = new CustomXid(1);
     Xid xid2 = new CustomXid(2);
     Xid xid3 = new CustomXid(3);

     xaRes.start(xid1, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = 'aa'");
     xaRes.end(xid1, XAResource.TMSUCCESS);

     xaRes.start(xid2, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa2 SET foo = 'bb'");
     xaRes.end(xid2, XAResource.TMSUCCESS);

     xaRes.start(xid3, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa3 SET foo = 'cc'");
     xaRes.end(xid3, XAResource.TMSUCCESS);

     xaRes.commit(xid1, true);
     xaRes.commit(xid2, true);
     xaRes.commit(xid3, true);
    }
    */
}
