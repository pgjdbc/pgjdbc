/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.test.TestUtil;

import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;

public class IsValidTest {

  private TransactionState getTransactionState(Connection conn) {
    return ((BaseConnection) conn).getTransactionState();
  }

  @Test
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
  @Test
  public void testTransactionState() throws Exception {
    Connection conn = TestUtil.openDB();
    try {
      TransactionState transactionState;
      transactionState = getTransactionState(conn);
      conn.isValid(0);
      assertEquals("Transaction state has been changed", transactionState,
          getTransactionState(conn));

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      try {
        transactionState = getTransactionState(conn);
        conn.isValid(0);
        assertEquals("Transaction state has been changed", transactionState,
            getTransactionState(conn));

        Statement stmt = conn.createStatement();
        stmt.execute("SELECT 1");
        transactionState = getTransactionState(conn);
        conn.isValid(0);
        assertEquals("Transaction state has been changed", transactionState,
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
