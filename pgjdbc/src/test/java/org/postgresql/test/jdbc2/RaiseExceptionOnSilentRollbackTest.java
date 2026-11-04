/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * With {@code raiseExceptionOnSilentRollback=true}, {@link Connection#commit()} throws
 * {@link PSQLState#IN_FAILED_SQL_TRANSACTION} when the server completes {@code COMMIT} with the
 * {@code ROLLBACK} tag, and leaves the connection idle and usable.
 *
 * <p>Each test inserts the row {@code 1} and then, where the transaction has to fail, runs a
 * statement that divides by zero. Whether the row was committed is read from a separate
 * connection.
 */
class RaiseExceptionOnSilentRollbackTest {
  private static final String TABLE = "silent_rollback_test";

  private Connection observer;

  @BeforeEach
  void setUp() throws SQLException {
    observer = TestUtil.openDB();
    TestUtil.createTable(observer, TABLE, "id int");
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.dropTable(observer, TABLE);
    observer.close();
  }

  private static Properties props(boolean raiseExceptionOnSilentRollback, AutoSave autoSave) {
    Properties props = new Properties();
    PGProperty.RAISE_EXCEPTION_ON_SILENT_ROLLBACK.set(props, raiseExceptionOnSilentRollback);
    PGProperty.AUTOSAVE.set(props, autoSave.value());
    return props;
  }

  private static Connection openDB(boolean raiseExceptionOnSilentRollback, AutoSave autoSave)
      throws SQLException {
    return TestUtil.openDB(props(raiseExceptionOnSilentRollback, autoSave));
  }

  /**
   * Inserts the row {@code 1} in a new transaction.
   */
  private static void insertRow(Connection con) throws SQLException {
    con.setAutoCommit(false);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("insert into " + TABLE + " select 1");
    }
  }

  /**
   * Inserts the row {@code 1} in a new transaction, then runs a statement that fails with
   * division by zero.
   */
  private static void insertRowAndFail(Connection con) throws SQLException {
    insertRow(con);
    try (Statement stmt = con.createStatement()) {
      SQLException e = assertThrows(SQLException.class, () -> stmt.execute("select 1/0"));
      assertEquals(PSQLState.DIVISION_BY_ZERO.getState(), e.getSQLState(), "select 1/0");
    }
  }

  private int committedRows() throws SQLException {
    try (Statement stmt = observer.createStatement();
         ResultSet rs = stmt.executeQuery("select count(*) from " + TABLE)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  private static TransactionState transactionState(Connection con) throws SQLException {
    return con.unwrap(BaseConnection.class).getTransactionState();
  }

  /**
   * Asserts the exception the driver throws for a silent rollback: SQLState {@code 25P02}, with
   * the division by zero that failed the transaction as the cause.
   */
  private static void assertSilentRollbackException(SQLException e, String call) {
    assertAll(
        () -> assertInstanceOf(PSQLException.class, e, "exception class of " + call),
        () -> assertEquals(PSQLState.IN_FAILED_SQL_TRANSACTION.getState(), e.getSQLState(),
            "SQLState of " + call),
        () -> assertEquals(PSQLState.DIVISION_BY_ZERO.getState(),
            assertInstanceOf(SQLException.class, e.getCause(), "cause of " + call).getSQLState(),
            "SQLState of the cause of " + call)
    );
  }

  @Test
  void commitOfAFailedTransactionReturnsWhenTheFlagIsOff() throws SQLException {
    try (Connection con = openDB(false, AutoSave.NEVER)) {
      insertRowAndFail(con);
      con.commit();
      assertAll(
          () -> assertEquals(TransactionState.IDLE, transactionState(con), "state after commit()"),
          () -> assertEquals(0, committedRows(), "rows committed")
      );
    }
  }

  @ParameterizedTest
  @EnumSource(value = AutoSave.class, names = {"NEVER", "CONSERVATIVE"})
  void commitOfAFailedTransactionThrowsWithTheFirstErrorAsCause(AutoSave autoSave)
      throws SQLException {
    try (Connection con = openDB(true, autoSave)) {
      insertRowAndFail(con);
      SQLException e = assertThrows(SQLException.class, con::commit);
      assertAll(
          () -> assertSilentRollbackException(e, "commit()"),
          () -> assertEquals(TransactionState.IDLE, transactionState(con), "state after commit()"),
          () -> assertEquals(0, committedRows(), "rows committed")
      );
    }
  }

  @Test
  void connectionIsUsableAfterTheCommitException() throws SQLException {
    try (Connection con = openDB(true, AutoSave.NEVER)) {
      insertRowAndFail(con);
      assertThrows(SQLException.class, con::commit);
      con.rollback();
      assertEquals(TransactionState.IDLE, transactionState(con), "state after rollback()");
      insertRow(con);
      con.commit();
      assertEquals(1, committedRows(), "rows committed by the transaction after the exception");
    }
  }

  @Test
  void commitOfAHealthyTransactionSucceedsWhenTheFlagIsOn() throws SQLException {
    try (Connection con = openDB(true, AutoSave.NEVER)) {
      insertRow(con);
      con.commit();
      assertEquals(1, committedRows(), "rows committed");
    }
  }

  @Test
  void autosaveAlwaysKeepsTheTransactionCommittable() throws SQLException {
    try (Connection con = openDB(true, AutoSave.ALWAYS)) {
      insertRowAndFail(con);
      con.commit();
      assertEquals(1, committedRows(), "rows committed");
    }
  }

  @Test
  void setAutoCommitTrueOnAFailedTransactionReturnsWhenTheFlagIsOff() throws SQLException {
    try (Connection con = openDB(false, AutoSave.NEVER)) {
      insertRowAndFail(con);
      con.setAutoCommit(true);
      assertAll(
          () -> assertTrue(con.getAutoCommit(), "getAutoCommit() after setAutoCommit(true)"),
          () -> assertEquals(TransactionState.IDLE, transactionState(con),
              "state after setAutoCommit(true)"),
          () -> assertEquals(0, committedRows(), "rows committed")
      );
    }
  }

  /**
   * The exception leaves auto-commit off, as a failed {@code COMMIT} does in
   * {@code setAutoCommit(true)}.
   */
  @Test
  void setAutoCommitTrueOnAFailedTransactionThrowsAndLeavesAutoCommitOff() throws SQLException {
    try (Connection con = openDB(true, AutoSave.NEVER)) {
      insertRowAndFail(con);
      SQLException e = assertThrows(SQLException.class, () -> con.setAutoCommit(true));
      assertAll(
          () -> assertSilentRollbackException(e, "setAutoCommit(true)"),
          () -> assertFalse(con.getAutoCommit(), "getAutoCommit() after the exception"),
          () -> assertEquals(TransactionState.IDLE, transactionState(con),
              "state after setAutoCommit(true)"),
          () -> assertEquals(0, committedRows(), "rows committed")
      );
    }
  }

  /**
   * The server transaction has ended with the exception, so a second {@code setAutoCommit(true)},
   * as a pool sends when it resets the connection, has nothing to commit.
   */
  @Test
  void setAutoCommitTrueSucceedsWhenRepeatedAfterTheException() throws SQLException {
    try (Connection con = openDB(true, AutoSave.NEVER)) {
      insertRowAndFail(con);
      assertThrows(SQLException.class, () -> con.setAutoCommit(true));
      con.setAutoCommit(true);
      assertAll(
          () -> assertTrue(con.getAutoCommit(),
              "getAutoCommit() after the second setAutoCommit(true)"),
          () -> assertEquals(TransactionState.IDLE, transactionState(con),
              "state after the second setAutoCommit(true)"),
          () -> assertEquals(0, committedRows(), "rows committed")
      );
    }
  }

  /**
   * With {@code readOnly=true} and {@code readOnlyMode=always}, enabling auto-commit makes the
   * session read-only, and the second {@code setAutoCommit(true)} after the exception still does.
   */
  @Test
  void setAutoCommitTrueRepeatedAfterTheExceptionMakesTheSessionReadOnly() throws SQLException {
    Properties props = props(true, AutoSave.NEVER);
    PGProperty.READ_ONLY_MODE.set(props, "always");
    try (Connection con = TestUtil.openDB(props)) {
      con.setReadOnly(true);
      con.setAutoCommit(false);
      try (Statement stmt = con.createStatement()) {
        SQLException e = assertThrows(SQLException.class, () -> stmt.execute("select 1/0"));
        assertEquals(PSQLState.DIVISION_BY_ZERO.getState(), e.getSQLState(), "select 1/0");
      }
      assertThrows(SQLException.class, () -> con.setAutoCommit(true));
      con.setAutoCommit(true);
      try (Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery("show transaction_read_only")) {
        rs.next();
        assertEquals("on", rs.getString(1),
            "show transaction_read_only after the second setAutoCommit(true)");
      }
    }
  }
}
