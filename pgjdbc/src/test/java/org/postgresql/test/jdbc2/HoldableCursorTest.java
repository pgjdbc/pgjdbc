/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Tests for holdable cursors (cursors that survive COMMIT).
 */
public class HoldableCursorTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "test_holdable", "id int, value text");
    con.setAutoCommit(false);
  }

  @Override
  public void tearDown() throws SQLException {
    if (!con.getAutoCommit()) {
      con.rollback();
    }
    con.setAutoCommit(true);
    TestUtil.dropTable(con, "test_holdable");
    super.tearDown();
  }

  @Test
  public void testHoldableCursorSurvivesCommit() throws Exception {
    // Insert test data
    con.close();
    Properties props = new Properties();
    PGProperty.PROTOCOL_VERSION.set(props,"3.3");
    con = TestUtil.openDB(props);
    PreparedStatement insert = con.prepareStatement("INSERT INTO test_holdable VALUES (?, ?)");
    for (int i = 1; i <= 100; i++) {
      insert.setInt(1, i);
      insert.setString(2, "value_" + i);
      insert.addBatch();
    }
    insert.executeBatch();

    con.setAutoCommit(false);
    // Create holdable cursor
    PreparedStatement stmt = con.prepareStatement(
        "SELECT id, value FROM test_holdable WHERE id > ? ORDER BY id",
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.HOLD_CURSORS_OVER_COMMIT
    );
    stmt.setInt(1, 50);
    stmt.setFetchSize(10);

    ResultSet rs = stmt.executeQuery();

    // Fetch first 10 rows before commit
    int count = 0;
    while (count < 10 && rs.next() ) {
      assertEquals(51 + count, rs.getInt("id"));
      assertEquals("value_" + (51 + count), rs.getString("value"));
      System.out.println("Value: "+ rs.getInt("id"));
      count++;
    }
    assertEquals(10, count);

    // COMMIT - cursor should survive
    con.commit();

    // Continue fetching after commit
    count = 0;
    while (count < 10 && rs.next()) {
      System.out.println("Value: "+ rs.getInt("id"));
      assertEquals(61 + count, rs.getInt("id"));
      assertEquals("value_" + (61 + count), rs.getString("value"));
      count++;
    }
    assertEquals(10, count);

    rs.close();
    stmt.close();
  }

  @Test
  public void testNonHoldableCursorClosesOnCommit() throws Exception {
    // Insert test data
    PreparedStatement insert = con.prepareStatement("INSERT INTO test_holdable VALUES (?, ?)");
    for (int i = 1; i <= 50; i++) {
      insert.setInt(1, i);
      insert.setString(2, "value_" + i);
      insert.addBatch();
    }
    insert.executeBatch();
    con.commit();

    // Create non-holdable cursor (default)
    PreparedStatement stmt = con.prepareStatement(
        "SELECT id, value FROM test_holdable ORDER BY id",
        ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_READ_ONLY,
        ResultSet.CLOSE_CURSORS_AT_COMMIT
    );
    stmt.setFetchSize(10);

    ResultSet rs = stmt.executeQuery();

    // Fetch some rows
    assertTrue(rs.next());
    assertEquals(1, rs.getInt("id"));

    // COMMIT - cursor should close
    con.commit();

    // Attempting to fetch should fail or return false
    boolean hasMore = rs.next();
    // After commit, non-holdable cursor is closed
    assertTrue(!hasMore || rs.isClosed());

    rs.close();
    stmt.close();
  }
}
