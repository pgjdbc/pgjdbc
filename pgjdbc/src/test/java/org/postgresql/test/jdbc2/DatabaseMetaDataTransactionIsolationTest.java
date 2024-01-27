/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

class DatabaseMetaDataTransactionIsolationTest {
  static Connection con;

  @BeforeAll
  static void setup() throws SQLException {
    con = TestUtil.openDB();
  }

  @AfterAll
  static void teardown() throws SQLException {
    TestUtil.closeDB(con);
  }

  @BeforeEach
  void resetTransactionIsolation() throws SQLException {
    // Restore to defaults
    con.setAutoCommit(true);
    try (Statement st = con.createStatement()) {
      st.execute("alter database test set default_transaction_isolation to DEFAULT");
    }
  }

  @Test
  void connectionTransactionIsolation() throws SQLException {
    // We use a new connection to avoid any side effects from other tests as we need to test
    // the default transaction isolation level.
    try (Connection con = TestUtil.openDB()) {
      assertIsolationEquals(
          "read committed",
          con.getTransactionIsolation(),
          () -> "Default connection transaction isolation in PostgreSQL is read committed");
    }
  }

  @Test
  void metadataDefaultTransactionIsolation() throws SQLException {
    assertIsolationEquals(
        "read committed",
        getDefaultTransactionIsolation(),
        () -> "Default database transaction isolation in PostgreSQL is read committed");
  }

  @ParameterizedTest
  @ValueSource(strings = {"read committed", "read uncommitted", "repeatable read", "serializable"})
  void alterDatabaseDefaultTransactionIsolation(String isolationLevel) throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute(
          "alter database test set default_transaction_isolation to '" + isolationLevel + "'");
    }

    assertIsolationEquals(
        isolationLevel,
        getDefaultTransactionIsolation(),
        () -> "Default transaction isolation should be " + isolationLevel);
  }

  /**
   * PostgreSQL does not seem to update the value in
   * pg_catalog.pg_settings WHERE name='default_transaction_isolation'
   * when changing default_transaction_isolation, so we reconnect to get the new value.
   */
  static int getDefaultTransactionIsolation() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      return con.getMetaData().getDefaultTransactionIsolation();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"read committed", "read uncommitted", "repeatable read", "serializable"})
  void alterConnectionTransactionIsolation(String isolationLevel) throws SQLException {
    con.setAutoCommit(false);
    try (Statement st = con.createStatement()) {
      st.execute("set transaction ISOLATION LEVEL " + isolationLevel);
    }

    assertIsolationEquals(
        isolationLevel,
        con.getTransactionIsolation(),
        () -> "Connection transaction isolation should be " + isolationLevel);
  }

  @ParameterizedTest
  @ValueSource(ints = {
      Connection.TRANSACTION_SERIALIZABLE,
      Connection.TRANSACTION_REPEATABLE_READ,
      Connection.TRANSACTION_READ_COMMITTED,
      Connection.TRANSACTION_READ_UNCOMMITTED})
  void setConnectionTransactionIsolation(int isolationLevel) throws SQLException {
    con.setAutoCommit(false);
    con.setTransactionIsolation(isolationLevel);

    assertIsolationEquals(
        mapJdbcIsolationToPg(isolationLevel),
        con.getTransactionIsolation(),
        () -> "Connection transaction isolation should be " + isolationLevel);
  }

  private static void assertIsolationEquals(String expected, int actual, Supplier<String> message) {
    assertEquals(
        expected,
        mapJdbcIsolationToPg(actual),
        message);
  }

  private static String mapJdbcIsolationToPg(int isolationLevel) {
    switch (isolationLevel) {
      case Connection.TRANSACTION_READ_COMMITTED:
        return "read committed";
      case Connection.TRANSACTION_READ_UNCOMMITTED:
        return "read uncommitted";
      case Connection.TRANSACTION_REPEATABLE_READ:
        return "repeatable read";
      case Connection.TRANSACTION_SERIALIZABLE:
        return "serializable";
      case Connection.TRANSACTION_NONE:
        return "none";
      default:
        return "Unknown isolation level " + isolationLevel;
    }
  }
}
