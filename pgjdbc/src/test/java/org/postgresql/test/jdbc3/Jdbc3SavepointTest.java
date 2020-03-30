/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

public class Jdbc3SavepointTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createTable(conn, "savepointtable", "id int primary key");
    conn.setAutoCommit(false);
  }

  @After
  public void tearDown() throws SQLException {
    conn.setAutoCommit(true);
    TestUtil.dropTable(conn, "savepointtable");
    TestUtil.closeDB(conn);
  }

  private boolean hasSavepoints() throws SQLException {
    return true;
  }

  private void addRow(int id) throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO savepointtable VALUES (?)");
    pstmt.setInt(1, id);
    pstmt.executeUpdate();
    pstmt.close();
  }

  private int countRows() throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM savepointtable");
    rs.next();
    int count = rs.getInt(1);
    rs.close();
    return count;
  }

  @Test
  public void testAutoCommitFails() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    conn.setAutoCommit(true);

    try {
      conn.setSavepoint();
      fail("Can't create a savepoint with autocommit.");
    } catch (SQLException sqle) {
    }

    try {
      conn.setSavepoint("spname");
      fail("Can't create a savepoint with autocommit.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testCantMixSavepointTypes() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    Savepoint namedSavepoint = conn.setSavepoint("named");
    Savepoint unNamedSavepoint = conn.setSavepoint();

    try {
      namedSavepoint.getSavepointId();
      fail("Can't get id from named savepoint.");
    } catch (SQLException sqle) {
    }

    try {
      unNamedSavepoint.getSavepointName();
      fail("Can't get name from unnamed savepoint.");
    } catch (SQLException sqle) {
    }

  }

  @Test
  public void testRollingBackToSavepoints() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    Savepoint empty = conn.setSavepoint();
    addRow(1);
    Savepoint onerow = conn.setSavepoint("onerow");
    addRow(2);

    assertEquals(2, countRows());
    conn.rollback(onerow);
    assertEquals(1, countRows());
    conn.rollback(empty);
    assertEquals(0, countRows());
  }

  @Test
  public void testGlobalRollbackWorks() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    conn.setSavepoint();
    addRow(1);
    conn.setSavepoint("onerow");
    addRow(2);

    assertEquals(2, countRows());
    conn.rollback();
    assertEquals(0, countRows());
  }

  @Test
  public void testContinueAfterError() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    addRow(1);
    Savepoint savepoint = conn.setSavepoint();
    try {
      addRow(1);
      fail("Should have thrown duplicate key exception");
    } catch (SQLException sqle) {
      conn.rollback(savepoint);
    }

    assertEquals(1, countRows());
    addRow(2);
    assertEquals(2, countRows());
  }

  @Test
  public void testReleaseSavepoint() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    Savepoint savepoint = conn.setSavepoint("mysavepoint");
    conn.releaseSavepoint(savepoint);
    try {
      savepoint.getSavepointName();
      fail("Can't use savepoint after release.");
    } catch (SQLException sqle) {
    }

    savepoint = conn.setSavepoint();
    conn.releaseSavepoint(savepoint);
    try {
      savepoint.getSavepointId();
      fail("Can't use savepoint after release.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testComplicatedSavepointName() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    Savepoint savepoint = conn.setSavepoint("name with spaces + \"quotes\"");
    conn.rollback(savepoint);
    conn.releaseSavepoint(savepoint);
  }

  @Test
  public void testRollingBackToInvalidSavepointFails() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    Savepoint sp1 = conn.setSavepoint();
    Savepoint sp2 = conn.setSavepoint();

    conn.rollback(sp1);
    try {
      conn.rollback(sp2);
      fail("Can't rollback to a savepoint that's invalid.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testRollbackMultipleTimes() throws SQLException {
    if (!hasSavepoints()) {
      return;
    }

    addRow(1);
    Savepoint savepoint = conn.setSavepoint();

    addRow(2);
    conn.rollback(savepoint);
    assertEquals(1, countRows());

    conn.rollback(savepoint);
    assertEquals(1, countRows());

    addRow(2);
    conn.rollback(savepoint);
    assertEquals(1, countRows());

    conn.releaseSavepoint(savepoint);
    assertEquals(1, countRows());
  }

}
