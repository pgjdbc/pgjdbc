/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.xa;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.xa.PGXADataSource;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.Callable;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;

public class XAPrepareFailureTest extends BaseTest4 {
  private XADataSource xaDs;

  private XAConnection xaconn;
  private XAResource xaRes;
  private Connection conn;
  XADataSourceTest.CustomXid xid = new XADataSourceTest.CustomXid(7);

  public XAPrepareFailureTest() {
    xaDs = new PGXADataSource();
    BaseDataSourceTest.setupDataSource((PGXADataSource) xaDs);
  }

  @Before
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(isPreparedTransactionEnabled(con));

    TestUtil.createTable(con, "xatransaction_test_table", "i int");

    clearAllPrepared();
    xaconn = xaDs.getXAConnection();
    xaRes = xaconn.getXAResource();
    conn = xaconn.getConnection();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "xatransaction_test_table");
    clearAllPrepared();
    super.tearDown();
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

  private void clearAllPrepared() throws SQLException {
    Statement st = con.createStatement();
    try {
      ResultSet rs = st.executeQuery(
          "SELECT x.gid, x.owner = current_user "
          + "FROM pg_prepared_xacts x "
          + "WHERE x.database = current_database()");

      Statement st2 = con.createStatement();
      while (rs.next()) {
        // TODO: This should really use org.junit.Assume once we move to JUnit 4
        assertTrue("Only prepared xacts owned by current user may be present in db",
            rs.getBoolean(2));
        st2.executeUpdate("ROLLBACK PREPARED '" + rs.getString(1) + "'");
      }
      TestUtil.closeQuietly(st2);
    } finally {
      TestUtil.closeQuietly(st);
    }
  }

  private XADataSourceTest.CustomXid setupTransaction() throws SQLException, XAException {
    xaRes.start(xid, XAResource.TMNOFLAGS);
    Statement st = conn.createStatement();
    st.execute("insert into xatransaction_test_table(i) values(42)");
    try {
      st.execute("invalid sql to abort transaction");
    } catch (SQLException ignored) {
    }
    TestUtil.closeQuietly(st);
    return xid;
  }

  private void assertFails(Callable<Void> action) throws Exception {
    String msg = "prepare transaction should fail since the transaction is in invalid state";
    try {
      action.call();
      Assert.fail(msg);
    } catch (XAException e) {
      try {
        Assert.assertEquals(msg, XAException.XA_RBOTHER, e.errorCode);
      } catch (Throwable t) {
        t.initCause(e);
        throw t;
      }
    }
  }

  @Test
  public void prepareShouldFailIfTransactionWasInvalidState() throws Exception {
    setupTransaction();
    assertFails(new Callable<Void>() {
      @Override
      public Void call() throws Exception {
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        return null;
      }
    });
  }

  @Test
  public void execute_rollback_statement_shouldsucceed() throws Exception {
    setupTransaction();
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.rollback(xid);
  }
}
