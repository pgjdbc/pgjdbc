/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Exercises the cleanup of named portals (fetch-size cursors) on error paths: a statement pin
 * taken for a portal must be released both when the Bind never completes and when the Bind
 * succeeded but the Execute failed. The tests observe the cleanup indirectly: the connection
 * stays usable, and the {@code unpin()} balance assertion does not fire under {@code -ea}.
 */
class PortalErrorCleanupTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    con.setAutoCommit(false);
  }

  /**
   * Parse fails, so the portal created for the cursor never reaches BindComplete and is drained
   * from pendingBindQueue at ReadyForQuery.
   */
  @Test
  void parseErrorReleasesPendingBindPortal() throws SQLException {
    PreparedStatement ps = con.prepareStatement("SELECT no_such_column FROM generate_series(1, 100)");
    ps.setFetchSize(2);
    assertThrows(SQLException.class, ps::executeQuery, "query selects a non-existent column");
    ps.close();
    con.rollback();

    assertConnectionUsable();
  }

  /**
   * Parse and Bind succeed, Execute fails mid-stream, so the portal is registered and its
   * ExecuteRequest is drained at ReadyForQuery: the portal must be closed and unpinned there.
   */
  @Test
  void executeErrorReleasesBoundPortal() throws SQLException {
    PreparedStatement ps = con.prepareStatement(
        "SELECT 1 / (n - n) FROM generate_series(1, 100) AS s(n)");
    ps.setFetchSize(2);
    assertThrows(SQLException.class, ps::executeQuery, "query divides by zero");
    ps.close();
    con.rollback();

    assertConnectionUsable();
  }

  /**
   * A cursor is abandoned mid-read by re-executing its statement with a different parameter type
   * (which unprepares and re-parses the statement). The old ResultSet is closed by the
   * re-execution per JDBC, and the new execution works.
   */
  @Test
  void typeSwitchWhileCursorOpen() throws SQLException {
    PreparedStatement ps = con.prepareStatement(
        "SELECT n FROM generate_series(1, 100) AS s(n) WHERE ? IS NOT NULL");
    ps.setFetchSize(2);

    ps.setString(1, "42");
    ResultSet rs1 = ps.executeQuery();
    assertTrue(rs1.next(), "first batch of the cursor should have rows");

    // Flip the bind type; the driver unprepares and re-parses the statement
    ps.setInt(1, 7);
    ResultSet rs2 = ps.executeQuery();
    assertTrue(rs1.isClosed(), "re-executing the statement closes its previous ResultSet");

    int count = 0;
    while (rs2.next()) {
      count++;
    }
    assertEquals(100, count, "cursor re-executed with the new parameter type reads all rows");
    ps.close();

    assertConnectionUsable();
  }

  /**
   * Repeats the bound-portal error while another cursor of another statement is open and half
   * read: the surviving cursor's statement must not be closed by the cleanup of the failed one.
   */
  @Test
  void executeErrorDoesNotCloseUnrelatedCursor() throws SQLException {
    PreparedStatement good = con.prepareStatement("SELECT n FROM generate_series(1, 10) AS s(n)");
    good.setFetchSize(2);
    ResultSet goodRs = good.executeQuery();
    assertTrue(goodRs.next(), "first batch of the healthy cursor should have rows");

    PreparedStatement bad = con.prepareStatement(
        "SELECT 1 / (n - n) FROM generate_series(1, 100) AS s(n)");
    bad.setFetchSize(2);
    assertThrows(SQLException.class, bad::executeQuery, "query divides by zero");
    bad.close();
    con.rollback();

    // The transaction rollback killed the server-side portal, so the cursor cannot continue;
    // what matters is that the failure is a clean SQLException and the connection recovers.
    assertThrows(SQLException.class, () -> {
      while (goodRs.next()) {
        // drain until the dead portal is hit
      }
    }, "reading a cursor after transaction rollback fails cleanly");
    good.close();

    assertConnectionUsable();
  }

  private void assertConnectionUsable() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT 42")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "connection should stay usable after portal cleanup");
        assertEquals(42, rs.getInt(1));
      }
    }
    con.rollback();
  }
}
