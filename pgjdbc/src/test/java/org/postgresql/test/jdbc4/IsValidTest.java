/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.TransactionState;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;

public class IsValidTest extends BaseTest4 {
  @Test
  public void testIsValidShouldNotModifyTransactionStateOutsideTransaction() throws SQLException {
    TransactionState initialTransactionState = TestUtil.getTransactionState(con);
    assertTrue(con.isValid(0), "Connection should be valid");
    TestUtil.assertTransactionState("Transaction state should not be modified by non-transactional Connection.isValid(...)", con, initialTransactionState);
  }

  @Test
  public void testIsValidShouldNotModifyTransactionStateInEmptyTransaction() throws SQLException {
    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    con.setAutoCommit(false);
    TransactionState transactionState = TestUtil.getTransactionState(con);
    assertTrue(con.isValid(0), "Connection should be valid");
    TestUtil.assertTransactionState("Transaction state should not be modified by Connection.isValid(...) within an empty transaction", con, transactionState);
  }

  @Test
  public void testIsValidShouldNotModifyTransactionStateInNonEmptyTransaction() throws SQLException {
    con.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    con.setAutoCommit(false);
    TestUtil.executeQuery(con, "SELECT 1");
    TransactionState transactionState = TestUtil.getTransactionState(con);
    assertTrue(con.isValid(0), "Connection should be valid");
    TestUtil.assertTransactionState("Transaction state should not be modified by Connection.isValid(...) within a non-empty transaction", con, transactionState);
  }

  @Test
  public void testIsValidRemoteClose() throws SQLException, InterruptedException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4), "Unable to use pg_terminate_backend(...) before version 8.4");

    boolean wasTerminated = TestUtil.terminateBackend(con);
    assertTrue(wasTerminated, "The backend should be terminated");

    // Keeps checking for up to 5-seconds that the connection is marked invalid
    for (int i = 0; i < 500; i++) {
      if (!con.isValid(0)) {
        break;
      }
      // Wait a bit to give the connection a chance to gracefully handle the termination
      Thread.sleep(10);
    }
    assertFalse(con.isValid(0), "The terminated connection should not be valid");
  }
}
