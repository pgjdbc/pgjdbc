package org.postgresql.test.jdbc4;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;
import org.junit.Assert;

import java.sql.Connection;
import java.sql.Statement;

public class IsValidTest extends TestCase {

  private int getTransactionState(Connection conn) {
    return ((BaseConnection) conn).getTransactionState();
  }

  public void testIsValid() throws Exception {
    Connection _conn = TestUtil.openDB();
    try {
      assertTrue(_conn.isValid(0));
    } finally {
      TestUtil.closeDB(_conn);
    }
    assertFalse(_conn.isValid(0));
  }

  /**
   * Test that the transaction state is left unchanged
   */
  public void testTransactionState() throws Exception {
    Connection conn = TestUtil.openDB();
    try {
      int transactionState;
      transactionState = getTransactionState(conn);
      conn.isValid(0);
      Assert.assertEquals("Transaction state has been changed",
          transactionState,
          getTransactionState(conn));

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      try {
        transactionState = getTransactionState(conn);
        conn.isValid(0);
        Assert.assertEquals("Transaction state has been changed",
            transactionState,
            getTransactionState(conn));

        Statement stmt = conn.createStatement();
        stmt.execute("SELECT 1");
        transactionState = getTransactionState(conn);
        conn.isValid(0);
        Assert.assertEquals("Transaction state has been changed",
            transactionState,
            getTransactionState(conn));
      } finally {
        try {
          conn.setAutoCommit(true);
        } catch (final Exception e) {
        }
      }
    } finally {
      TestUtil.closeDB(conn);
    }
  }

}
