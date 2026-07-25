/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.TransactionState;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

/**
 * Tests that cursor-based fetching works when a transaction is started via SQL commands
 * ({@code START TRANSACTION}, {@code BEGIN}) instead of {@code setAutoCommit(false)}.
 *
 * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3993">issue 3993</a>
 */
@Isolated("pg_cursors counts are unreliable when tests execute concurrently")
class CursorFetchSqlTransactionTest extends BaseTest4 {

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      PreferQueryMode mode = con.unwrap(BaseConnection.class).getPreferQueryMode();
      assumeTrue(
          mode != PreferQueryMode.SIMPLE && mode != PreferQueryMode.EXTENDED_FOR_PREPARED,
          "server-side cursors require extended query protocol for non-prepared statements");
      TestUtil.createTable(con, "test_fetch", "value integer");
      TestUtil.execute(con, "insert into test_fetch(value) select generate_series(0, 99)");
    }
  }

  @AfterAll
  static void dropTables() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "test_fetch");
    }
  }

  /**
   * Counts the number of pgjdbc server-side cursors (named portals with the C_ prefix)
   * currently open on the connection.
   */
  private int countPgjdbcCursors() throws SQLException {
    try (Statement check = con.createStatement();
         ResultSet rs = check.executeQuery(
             "SELECT count(*) FROM pg_cursors WHERE name LIKE 'C\\_%'");) {
      rs.next();
      return rs.getInt(1);
    }
  }

  static Stream<String> startTransactionOptions() {
    return Stream.of(
        "BEGIN",
        "START TRANSACTION",
        "START TRANSACTION READ ONLY"
    );
  }

  @ParameterizedTest
  @MethodSource("startTransactionOptions")
  void testCursorWithStartTransaction(String startTransaction) throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute(startTransaction);
      assertTrue(con.getAutoCommit(),
          "autoCommit should still report true after START TRANSACTION");
      assertEquals(TransactionState.OPEN,
          con.unwrap(BaseConnection.class).getTransactionState(),
          "connection should be in OPEN transaction state after START TRANSACTION");

      int cursorsBefore = countPgjdbcCursors();

      stmt.setFetchSize(10);
      try (ResultSet rs = stmt.executeQuery("SELECT * FROM test_fetch ORDER BY value")) {
        assertEquals(cursorsBefore + 1, countPgjdbcCursors(),
            () -> "a server-side cursor should be created when using fetchSize with " + startTransaction);

        int count = 0;
        while (rs.next()) {
          assertEquals(count, rs.getInt(1));
          count++;
        }

        assertEquals(100, count,
            () -> "cursor-based fetch with " + startTransaction + " should return all rows");
      }
    }
  }
}
