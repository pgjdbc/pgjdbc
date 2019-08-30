/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TransactionState;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class IsValidTest extends BaseTest4 {

  private TransactionState getTransactionState(Connection conn) {
    return ((BaseConnection) conn).getTransactionState();
  }

  @Test
  public void testIsValid() throws Exception {
    try {
      assertTrue(con.isValid(0));
    } finally {
      TestUtil.closeDB(con);
    }
    assertFalse(con.isValid(0));
  }

  /**
   * Test that the transaction state is left unchanged.
   */
  @Test
  public void testTransactionState() throws Exception {
    TransactionState transactionState = getTransactionState(con);
    con.isValid(0);
    assertEquals("Transaction state has been changed", transactionState,
        getTransactionState(con));

    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    con.setAutoCommit(false);
    try {
      transactionState = getTransactionState(con);
      con.isValid(0);
      assertEquals("Transaction state has been changed", transactionState,
          getTransactionState(con));

      Statement stmt = con.createStatement();
      stmt.execute("SELECT 1");
      transactionState = getTransactionState(con);
      con.isValid(0);
      assertEquals("Transaction state has been changed", transactionState,
          getTransactionState(con));
    } finally {
      try {
        con.setAutoCommit(true);
      } catch (final Exception e) {
      }
    }
  }

  @Test
  public void testIsValidRemoteClose() throws Exception {

    assumeMinimumServerVersion("Unable to use pg_terminate_backend before version 8.4", ServerVersion.v8_4);

    Properties props = new Properties();
    updateProperties(props);
    Connection con2 = TestUtil.openPrivilegedDB();

    try {

      assertTrue("First Connection should be valid", con.isValid(0));

      String pid;
      Statement s = con.createStatement();

      try {
        s.execute("select pg_backend_pid()");
        ResultSet rs = s.getResultSet();
        rs.next();
        pid = rs.getString(1);
      } finally {
        TestUtil.closeQuietly(s);
      }

      TestUtil.execute("select pg_terminate_backend(" + pid + ")", con2);

      assertFalse("The Second connection should now be invalid", con.isValid(0));

    } finally {
      TestUtil.closeQuietly(con2);
    }
  }
}
