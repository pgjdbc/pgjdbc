/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * A result over {@code maxResultBuffer} fails the query with SQLState 08S01 and leaves the
 * connection usable, and the limit applies to the rows of one round trip to the server: one
 * statement, or one fetch batch when the result is read through a cursor. COPY output does not
 * count against the limit.
 *
 * <p>Needs a running server.</p>
 */
class MaxResultBufferConnectionTest {
  private static final String SINGLE_ROW =
      "Result set exceeded maxResultBuffer limit. A single row of {0} bytes exceeds the limit of {1} bytes, so the row was skipped.";
  private static final String BUFFERED_ROWS =
      "Result set exceeded maxResultBuffer limit. The rows buffered since the last ReadyForQuery hold {0} bytes, so a row of {1} bytes does not fit within the limit of {2} bytes and was skipped.";

  /** Ten rows of 100 bytes each: 1000 bytes in total. */
  private static final String TEN_ROWS = "select repeat('x', 100) from generate_series(1, 10)";

  private static Connection openWithLimit(String maxResultBuffer) throws SQLException {
    Properties props = new Properties();
    PGProperty.MAX_RESULT_BUFFER.set(props, maxResultBuffer);
    return TestUtil.openDB(props);
  }

  @Test
  void aRowOverTheLimitFailsTheQueryAndTheNextQueryOnTheConnectionSucceeds() throws SQLException {
    try (Connection con = openWithLimit("100");
         Statement stmt = con.createStatement()) {
      SQLException e = assertThrows(SQLException.class,
          () -> stmt.executeQuery("select repeat('x', 1000)"));

      int next;
      try (ResultSet rs = stmt.executeQuery("select 42")) {
        rs.next();
        next = rs.getInt(1);
      }

      assertAll(
          () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
          () -> assertEquals(GT.tr(SINGLE_ROW, "1000", "100"), e.getMessage()),
          () -> assertFalse(con.isClosed(), "isClosed()"),
          () -> assertEquals(42, next, "select 42 after the failure"));
    }
  }

  /**
   * The first statement fails with a division by zero at its third row, after rows of 101 and 102
   * bytes have arrived. The second returns one 100-byte row, which fits within 250 bytes only if
   * the count restarted.
   */
  @Test
  void theCountRestartsAfterAStatementThatFailedOnTheServerAfterSomeRows() throws SQLException {
    try (Connection con = openWithLimit("250");
         Statement stmt = con.createStatement()) {
      SQLException serverError = assertThrows(SQLException.class, () -> stmt.executeQuery(
          "select repeat('x', 100) || (1 / (n - 3))::text from generate_series(1, 5) n"));
      assertEquals(PSQLState.DIVISION_BY_ZERO.getState(), serverError.getSQLState(),
          "SQLState of the failing statement");

      String value;
      try (ResultSet rs = stmt.executeQuery("select repeat('y', 100)")) {
        rs.next();
        value = rs.getString(1);
      }

      assertEquals(100, value.length(), "length of the value the second statement returned");
    }
  }

  /**
   * A {@code Statement} with a fetch size reads through a cursor only under
   * {@code preferQueryMode=extended} or {@code extendedCacheEverything}.
   */
  private static void assumeStatementReadsThroughACursor(Connection con) throws SQLException {
    PreferQueryMode mode = con.unwrap(PGConnection.class).getPreferQueryMode();
    assumeTrue(mode == PreferQueryMode.EXTENDED || mode == PreferQueryMode.EXTENDED_CACHE_EVERYTHING,
        () -> "a Statement reads through a cursor only in extended mode, but preferQueryMode is "
            + mode);
  }

  @Test
  void withAFetchSizeTheLimitAppliesToEachBatch() throws SQLException {
    try (Connection con = openWithLimit("250")) {
      assumeStatementReadsThroughACursor(con);
      con.setAutoCommit(false);
      int rows = 0;
      try (Statement stmt = con.createStatement()) {
        stmt.setFetchSize(2);
        try (ResultSet rs = stmt.executeQuery(TEN_ROWS)) {
          while (rs.next()) {
            rows++;
          }
        }
      }

      assertEquals(10, rows, "rows read in batches of 2");
    }
  }

  /**
   * Without a fetch size the same ten rows arrive in one round trip, and the third row does not
   * fit after the first two.
   */
  @Test
  void withoutAFetchSizeTheSameResultExceedsTheLimit() throws SQLException {
    try (Connection con = openWithLimit("250")) {
      assumeStatementReadsThroughACursor(con);
      con.setAutoCommit(false);
      try (Statement stmt = con.createStatement()) {
        SQLException e = assertThrows(SQLException.class, () -> stmt.executeQuery(TEN_ROWS));

        assertEquals(GT.tr(BUFFERED_ROWS, "200", "100", "250"), e.getMessage());
      }
    }
  }

  /**
   * Fifty COPY rows of 10001 bytes each, 500050 bytes in total, arrive under a limit of 4096 bytes
   * and within the 64 MB CopyData limit. A query after the COPY then reads a row under the limit.
   */
  @Test
  void copyOutputDoesNotCountAgainstTheLimit() throws Exception {
    try (Connection con = openWithLimit("4096")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      long rows = con.unwrap(PGConnection.class).getCopyAPI().copyOut(
          "copy (select repeat('x', 10000) from generate_series(1, 50)) to stdout", out);

      String value;
      try (Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery("select repeat('y', 100)")) {
        rs.next();
        value = rs.getString(1);
      }

      assertAll(
          () -> assertEquals(50, rows, "rows copied"),
          () -> assertEquals(500050, out.size(), "bytes copied"),
          () -> assertFalse(con.isClosed(), "isClosed()"),
          () -> assertEquals(100, value.length(), "length of the value the query after COPY returned"));
    }
  }
}
