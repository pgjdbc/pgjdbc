/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * {@code maxServerPreparedStatements} bounds the named statements a connection keeps across all
 * SQL texts: the least recently used ones are closed at the start of a later execution, and a
 * statement backing an open cursor is exempt until the cursor closes.
 */
class MaxServerPreparedStatementsTest extends BaseTest4 {

  private static final String MARKER = "/*MaxServerPreparedStatementsTest*/";

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.MAX_SERVER_PREPARED_STATEMENTS.set(props, "2");
  }

  @Test
  void leastRecentlyUsedStatementIsClosedAcrossQueries() throws SQLException {
    PreparedStatement psA = prepare("SELECT " + MARKER + " ? -- A");
    PreparedStatement psB = prepare("SELECT " + MARKER + " ? -- B");
    PreparedStatement psC = prepare("SELECT " + MARKER + " ? -- C");

    execute(psA, 1);
    execute(psB, 2);
    execute(psC, 3);
    // A is over the cap but the enforcement runs at the next execution boundary.

    triggerEnforcement();

    List<String> statements = serverStatements();
    assertEquals(2, statements.size(), "the cap of two statements must hold: " + statements);
    assertFalse(statements.toString().contains("-- A"),
        "the least recently used statement (A) must have been closed");
    assertTrue(statements.toString().contains("-- C"),
        "the most recently used statement (C) must survive");

    // The evicted statement's query re-prepares transparently on its next execution.
    execute(psA, 4);
    psA.close();
    psB.close();
    psC.close();
  }

  @Test
  void statementBackingAnOpenCursorIsExempt() throws SQLException {
    con.setAutoCommit(false);
    PreparedStatement cursorPs = prepare(
        "SELECT " + MARKER + " n FROM generate_series(1, 20) AS s(n) WHERE ? > 0 -- cursor");
    cursorPs.setFetchSize(2);
    cursorPs.setInt(1, 1);
    ResultSet cursor = cursorPs.executeQuery();
    assertTrue(cursor.next(), "first batch of the cursor should have rows");

    // Push two more statements: the connection is over the cap of 2, and the cursor's statement
    // is the least recently used one — but it is pinned and must be skipped.
    PreparedStatement psB = prepare("SELECT " + MARKER + " ? -- B");
    PreparedStatement psC = prepare("SELECT " + MARKER + " ? -- C");
    execute(psB, 2);
    execute(psC, 3);
    triggerEnforcement();

    assertTrue(serverStatements().toString().contains("-- cursor"),
        "the statement backing the open cursor must not be closed");

    // The cursor keeps fetching across the eviction pressure.
    int rows = 1;
    while (cursor.next()) {
      rows++;
    }
    assertEquals(20, rows, "the cursor must stay readable to the end");
    cursor.close();
    cursorPs.close();

    // During the pressure the cap was satisfied by closing B instead, so the connection is at
    // the cap now. Once the cursor is gone, new pressure evicts its statement: the exemption is
    // tied to the open cursor, not to the statement.
    execute(psB, 5);
    assertFalse(serverStatements().toString().contains("-- cursor"),
        "after the cursor closes, the statement is subject to the cap again");
    psB.close();
    psC.close();
    con.rollback();
  }

  private PreparedStatement prepare(String sql) throws SQLException {
    PreparedStatement ps = con.prepareStatement(sql);
    ps.unwrap(PGStatement.class).setPrepareThreshold(1);
    return ps;
  }

  private void execute(PreparedStatement ps, int value) throws SQLException {
    ps.setInt(1, value);
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
    }
  }

  /**
   * Runs a statement that creates no named statement of its own, so the only effect is the cap
   * enforcement in the execution preamble.
   */
  private void triggerEnforcement() throws SQLException {
    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery("SELECT 1")) {
      assertTrue(rs.next());
    }
  }

  private List<String> serverStatements() throws SQLException {
    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT statement FROM pg_prepared_statements"
                + " WHERE statement LIKE '%" + MARKER + "%' ORDER BY statement")) {
      List<String> statements = new ArrayList<>();
      while (rs.next()) {
        statements.add(rs.getString(1));
      }
      return statements;
    }
  }
}
