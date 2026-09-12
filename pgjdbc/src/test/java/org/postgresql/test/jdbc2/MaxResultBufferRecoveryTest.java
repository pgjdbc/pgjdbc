/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.copy.CopyManager;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Fails when a row over {@code maxResultBuffer} breaks the connection instead of being
 * skipped. The row is rejected before its body is read, so the driver stays on a message
 * boundary: the query fails with a usable error, and the connection is still good.
 */
class MaxResultBufferRecoveryTest {

  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    Properties props = new Properties();
    // Small enough that a single wide row exceeds it, large enough that the rows of the
    // driver's own setup queries do not.
    PGProperty.MAX_RESULT_BUFFER.set(props, "4096");
    con = TestUtil.openDB(props);
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  @Test
  void oversizedRowFailsTheQueryAndLeavesTheConnectionUsable() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      SQLException thrown = assertThrows(SQLException.class, () -> {
        try (ResultSet rs = stmt.executeQuery("select repeat('x', 100000)")) {
          rs.next();
        }
      });
      assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), thrown.getSQLState(),
          () -> "maxResultBuffer is a resource limit, not a protocol violation: "
              + thrown.getMessage());
      assertTrue(thrown.getMessage().contains("maxResultBuffer"),
          () -> "The error must name the setting that caused it: " + thrown.getMessage());
    }

    // The row was skipped rather than half-read, so the reader stayed on a message boundary
    // and consumed the rest of the response up to ReadyForQuery. A fresh query on the same
    // connection therefore still works -- an application can lower fetchSize (or the width
    // of what it selects) and carry on.
    assertFalse(con.isClosed(), "The connection must survive a resource-limit rejection");
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("select 42")) {
      assertTrue(rs.next());
      assertEquals(42, rs.getInt(1));
    }
  }

  @Test
  void severalOversizedRowsReportOnceAndLeaveTheConnectionUsable() throws SQLException {
    // Several wide rows in one result set is an ordinary shape -- a few documents in a text
    // column -- so every one of them is skipped, not just the first, and the connection
    // survives all of them. Only the first is reported, though: ResultHandlerBase chains
    // every exception it is handed, each with its own stack trace, so one per skipped row
    // would grow without bound and a wide enough result set would exhaust the heap while
    // reporting that the driver refused to spend the heap.
    int rows = 2000;
    try (Statement stmt = con.createStatement()) {
      SQLException thrown = assertThrows(SQLException.class, () -> {
        try (ResultSet rs = stmt.executeQuery(
            "select repeat('x', 10000) from generate_series(1, " + rows + ")")) {
          while (rs.next()) {
            rs.getString(1);
          }
        }
      });
      assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), thrown.getSQLState(),
          () -> "The caller must still see the resource limit, not a connection failure: "
              + thrown.getMessage());
      // Pin the message text, not just the state: an error that stopped naming the setting
      // would still carry the right SQLState and pass a state-only assertion.
      assertTrue(thrown.getMessage().contains("maxResultBuffer"),
          () -> "The error must name the setting that caused it: " + thrown.getMessage());

      assertEquals(0, chainedCount(thrown),
          () -> "One report per result set, not one per skipped row: " + rows
              + " rows produced a chain of " + (chainedCount(thrown) + 1) + " exceptions");
    }

    assertFalse(con.isClosed(),
        "Every over-sized row is skipped, so the connection survives all of them");
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("select 7")) {
      assertTrue(rs.next());
      assertEquals(7, rs.getInt(1));
    }
  }

  @Test
  void copyOutIsUnaffectedByTheCopyDataCeiling() throws Exception {
    // Every COPY row goes through the CopyData ceiling, so a check that fired below its own
    // limit would log once per row in the default mode and break COPY outright under
    // fail. The connection also carries a small maxResultBuffer, which deliberately does
    // not reach the COPY stream -- the other half of what this pins.
    Logger pgStreamLogger = Logger.getLogger("org.postgresql.core.PGStream");
    List<LogRecord> warnings = new ArrayList<>();
    Handler capture = new Handler() {
      @Override
      public void publish(LogRecord record) {
        if (record.getLevel() == Level.WARNING) {
          warnings.add(record);
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    capture.setLevel(Level.ALL);
    Level previousLevel = pgStreamLogger.getLevel();
    pgStreamLogger.setLevel(Level.ALL);
    pgStreamLogger.addHandler(capture);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("CREATE TEMP TABLE copyceiling (data text)");
      stmt.execute("INSERT INTO copyceiling SELECT 'row' || g FROM generate_series(1, 500) g");

      CopyManager manager = con.unwrap(PGConnection.class).getCopyAPI();
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      long rows = manager.copyOut("COPY copyceiling TO STDOUT", out);

      assertEquals(500, rows, "COPY must read every row");
      assertFalse(con.isClosed(), "COPY must not break the connection");
      assertTrue(warnings.isEmpty(),
          () -> "A CopyData within the ceiling must not be logged; got " + warnings.size()
              + " warnings, first: " + warnings.get(0).getMessage());
    } finally {
      pgStreamLogger.removeHandler(capture);
      pgStreamLogger.setLevel(previousLevel);
    }
  }

  @Test
  void copyOutOverTheCopyDataLimitReportsTheProperty() throws Exception {
    // The other direction of the same check, and the half a unit test cannot see:
    // readFromCopy rewrites an IOException into "Database connection failed when reading
    // from copy", so a limit that failed with one would reach the caller with its name only
    // in the cause.
    Properties props = new Properties();
    PGProperty.MAX_COPY_DATA_SIZE.set(props, "100");
    try (Connection tiny = TestUtil.openDB(props)) {
      try (Statement stmt = tiny.createStatement()) {
        stmt.execute("CREATE TEMP TABLE copylimit (data text)");
        stmt.execute("INSERT INTO copylimit VALUES (repeat('x', 5000))");
      }

      CopyManager manager = tiny.unwrap(PGConnection.class).getCopyAPI();
      SQLException thrown = assertThrows(SQLException.class,
          () -> manager.copyOut("COPY copylimit TO STDOUT", new ByteArrayOutputStream()));

      assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), thrown.getSQLState(),
          () -> "A configured limit is a resource limit, not a connection failure: "
              + thrown.getMessage());
      assertTrue(thrown.getMessage().contains("maxCopyDataSize"),
          () -> "The top-level message must name the property, not bury it in the cause: "
              + thrown.getMessage());
    }
  }

  @Test
  void oversizedRowInTheMiddleOfAResultSetDoesNotDesyncTheRest() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      // Three rows, the second of them over the limit. Reading the set has to fail, but the
      // driver must consume all three plus CommandComplete and ReadyForQuery in the process,
      // or the next query would read its response out of the middle of this one.
      assertThrows(SQLException.class, () -> {
        try (ResultSet rs = stmt.executeQuery(
            "select repeat('x', 10) union all select repeat('y', 100000)"
                + " union all select repeat('z', 10)")) {
          while (rs.next()) {
            rs.getString(1);
          }
        }
      });
    }

    assertFalse(con.isClosed());
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("select 'still here'")) {
      assertTrue(rs.next());
      assertEquals("still here", rs.getString(1));
    }
  }

  /** Number of exceptions chained behind {@code first} via {@code getNextException()}. */
  private static int chainedCount(SQLException first) {
    int n = 0;
    for (SQLException e = first.getNextException(); e != null; e = e.getNextException()) {
      n++;
    }
    return n;
  }
}
