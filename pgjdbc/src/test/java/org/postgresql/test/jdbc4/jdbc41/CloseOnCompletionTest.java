/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

class CloseOnCompletionTest {
  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createTable(conn, "table1", "id integer");
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.dropTable(conn, "table1");
    TestUtil.closeDB(conn);
  }

  /**
   * Test that the statement is not automatically closed if we do not ask for it.
   */
  @Test
  void withoutCloseOnCompletion() throws SQLException {
    Statement stmt = conn.createStatement();

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
    rs.close();
    assertFalse(stmt.isClosed());
  }

  /**
   * Test the behavior of closeOnCompletion with a single result set.
   */
  @Test
  void singleResultSet() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.closeOnCompletion();

    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
    rs.close();
    assertTrue(stmt.isClosed());
  }

  /**
   * Test the behavior of closeOnCompletion with a multiple result sets.
   */
  @Test
  void multipleResultSet() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.closeOnCompletion();

    stmt.execute(TestUtil.selectSQL("table1", "*") + ";" + TestUtil.selectSQL("table1", "*") + ";");
    ResultSet rs = stmt.getResultSet();
    rs.close();
    assertFalse(stmt.isClosed());
    stmt.getMoreResults();
    rs = stmt.getResultSet();
    rs.close();
    assertTrue(stmt.isClosed());
  }

  /**
   * Test that when execution does not produce any result sets, closeOnCompletion has no effect
   * (spec).
   */
  @Test
  void noResultSet() throws SQLException {
    Statement stmt = conn.createStatement();
    stmt.closeOnCompletion();

    stmt.executeUpdate(TestUtil.insertSQL("table1", "1"));
    assertFalse(stmt.isClosed());
  }

  @Test
  void executeTwice() throws SQLException {
    PreparedStatement s = conn.prepareStatement("SELECT 1");

    s.executeQuery();
    s.executeQuery();

  }

  @Test
  void closeOnCompletionExecuteTwice() throws SQLException {
    PreparedStatement s = conn.prepareStatement("SELECT 1");

    /*
     once we set close on completion we should only be able to execute one as the second execution
     will close the resultsets from the first one which will close the statement.
     */

    s.closeOnCompletion();
    s.executeQuery();
    try {
      s.executeQuery();
    } catch (SQLException ex) {
      assertEquals(PSQLState.OBJECT_NOT_IN_STATE.getState(), ex.getSQLState(), "Expecting <<This statement has been closed>>");
    }

  }
}
