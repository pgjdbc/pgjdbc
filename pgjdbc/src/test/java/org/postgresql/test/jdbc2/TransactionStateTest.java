/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.jdbc.AutoSave;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.stream.Stream;

/**
 * Tests for {@link TransactionState} transitions as reported by
 * {@link org.postgresql.core.QueryExecutor#getTransactionState()}.
 */
class TransactionStateTest extends BaseTest4 {

  private TransactionState getTransactionState() throws SQLException {
    return con.unwrap(BaseConnection.class).getTransactionState();
  }

  private boolean isAutoSaveAlways() throws SQLException {
    return con.unwrap(BaseConnection.class).getQueryExecutor().getAutoSave() == AutoSave.ALWAYS;
  }

  /**
   * Returns the expected state after a query error inside a transaction.
   * With {@code autosave=always} the driver automatically rolls back to a savepoint,
   * so the transaction stays OPEN instead of entering FAILED.
   */
  private TransactionState expectedStateAfterError() throws SQLException {
    return isAutoSaveAlways() ? TransactionState.OPEN : TransactionState.FAILED;
  }

  @Test
  void initialStateIsIdle() throws Exception {
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "a fresh connection should have IDLE transaction state");
  }

  @Test
  void idleBeforeFirstQueryWithAutoCommitFalse() throws Exception {
    con.setAutoCommit(false);
    // BEGIN is deferred until the first query, so state should still be IDLE
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "setAutoCommit(false) should not change transaction state before first query");
  }

  @Test
  void openAfterQueryWithAutoCommitFalse() throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
    }
    assertEquals(TransactionState.OPEN, getTransactionState(),
        "transaction state should be OPEN after executing a query with autoCommit=false");
  }

  static Stream<String> startTransactionCommands() {
    return Stream.of(
        "BEGIN",
        "START TRANSACTION",
        "START TRANSACTION READ ONLY"
    );
  }

  @ParameterizedTest
  @MethodSource("startTransactionCommands")
  void openAfterSqlBegin(String sql) throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute(sql);
    }
    assertEquals(TransactionState.OPEN, getTransactionState(),
        () -> "transaction state should be OPEN after " + sql);
  }

  @ParameterizedTest
  @ValueSource(strings = {"COMMIT", "ROLLBACK"})
  void idleAfterSqlEndTransaction(String sql) throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("BEGIN");
      stmt.execute(sql);
    }
    assertEquals(TransactionState.IDLE, getTransactionState(),
        () -> "transaction state should be IDLE after " + sql);
  }

  @Test
  void idleAfterJdbcCommit() throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
    }
    con.commit();
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "transaction state should be IDLE after Connection.commit()");
  }

  @Test
  void idleAfterJdbcRollback() throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
    }
    con.rollback();
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "transaction state should be IDLE after Connection.rollback()");
  }

  @Test
  void idleAfterSetAutoCommitTrue() throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
    }
    assertEquals(TransactionState.OPEN, getTransactionState());
    con.setAutoCommit(true);
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "transaction state should be IDLE after setAutoCommit(true)");
  }

  @Test
  void failedAfterErrorInTransaction() throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
      try {
        stmt.execute("SELECT * FROM nonexistent_table_xyz_12345");
      } catch (SQLException expected) {
        // expected
      }
    }
    assertEquals(expectedStateAfterError(), getTransactionState(),
        "transaction state after an error in a transaction");
  }

  @Test
  void idleAfterRollbackFromFailed() throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
      try {
        stmt.execute("SELECT * FROM nonexistent_table_xyz_12345");
      } catch (SQLException expected) {
        // expected
      }
    }
    assertEquals(expectedStateAfterError(), getTransactionState());
    con.rollback();
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "transaction state should be IDLE after rollback");
  }

  @Test
  void idleAfterErrorWithAutoCommitTrue() throws Exception {
    // With autoCommit=true each statement runs in its own implicit transaction,
    // so a failed statement should leave the connection IDLE (ReadyForQuery 'I'),
    // not FAILED
    try (Statement stmt = con.createStatement()) {
      try {
        stmt.execute("SELECT * FROM nonexistent_table_xyz_12345");
      } catch (SQLException expected) {
        // expected
      }
    }
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "error with autoCommit=true should leave state IDLE, not FAILED");
  }

  @Test
  void idleAfterCommitFromFailed() throws Exception {
    // PostgreSQL treats COMMIT in an aborted transaction as a transaction end
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
      try {
        stmt.execute("SELECT * FROM nonexistent_table_xyz_12345");
      } catch (SQLException expected) {
        // expected
      }
    }
    assertEquals(expectedStateAfterError(), getTransactionState());
    con.commit();
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "transaction state should be IDLE after commit()");
  }

  @Test
  void openAfterRollbackToSavepointFromFailed() throws Exception {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
      Savepoint sp = con.setSavepoint("sp1");
      try {
        stmt.execute("SELECT * FROM nonexistent_table_xyz_12345");
      } catch (SQLException expected) {
        // expected
      }
      assertEquals(expectedStateAfterError(), getTransactionState());
      con.rollback(sp);
    }
    assertEquals(TransactionState.OPEN, getTransactionState(),
        "ROLLBACK TO SAVEPOINT should move back to OPEN");
  }

  @Test
  void idleAfterCommitWhileIdleWithAutoCommitFalse() throws Exception {
    con.setAutoCommit(false);
    // No queries executed, so transaction state is IDLE (BEGIN not sent yet)
    assertEquals(TransactionState.IDLE, getTransactionState());
    con.commit();
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "commit() while IDLE with autoCommit=false should keep IDLE");
  }

  @Test
  void idleAfterRollbackWhileIdleWithAutoCommitFalse() throws Exception {
    con.setAutoCommit(false);
    assertEquals(TransactionState.IDLE, getTransactionState());
    con.rollback();
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "rollback() while IDLE with autoCommit=false should keep IDLE");
  }

  @Test
  void idleAfterSetAutoCommitTrueFromFailed() throws Exception {
    // setAutoCommit(true) routes through commit() via PgConnection.setAutoCommit,
    // which is a different path than calling commit() directly on a FAILED transaction
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("SELECT 1");
      try {
        stmt.execute("SELECT * FROM nonexistent_table_xyz_12345");
      } catch (SQLException expected) {
        // expected
      }
    }
    assertEquals(expectedStateAfterError(), getTransactionState());
    con.setAutoCommit(true);
    assertEquals(TransactionState.IDLE, getTransactionState(),
        "transaction state should be IDLE after setAutoCommit(true)");
  }
}
