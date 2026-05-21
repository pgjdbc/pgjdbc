/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Tests for implicit batch pipelining with async reading enabled.
 * Verifies that batch execution correctly handles success and error scenarios
 * when all queries are sent before processing any responses (pipeline mode).
 */
public class BatchPipelineTest {

  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    Properties props = new Properties();
    PGProperty.ASYNC_READING.set(props, true);
    con = TestUtil.openDB(props);
    try (Statement s = con.createStatement()) {
      TestUtil.createTempTable(con, "pipeline_test",
          "id serial PRIMARY KEY, val varchar(100)");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    if (con != null) {
      if (!con.getAutoCommit()) {
        con.rollback();
      }
      con.setAutoCommit(true);
      TestUtil.dropTable(con, "pipeline_test");
      TestUtil.closeDB(con);
    }
  }

  @Test
  void batchSucceeds() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(val) VALUES (?)")) {
      for (int i = 0; i < 100; i++) {
        ps.setString(1, "row-" + i);
        ps.addBatch();
      }
      int[] counts = ps.executeBatch();
      assertEquals(100, counts.length);
      for (int i = 0; i < counts.length; i++) {
        assertTrue(counts[i] == 1 || counts[i] == Statement.SUCCESS_NO_INFO,
            "Update count for row " + i + " should indicate success, got " + counts[i]);
      }
    }

    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM pipeline_test")) {
      assertTrue(rs.next());
      assertEquals(100, rs.getInt(1));
    }
  }

  @Test
  void batchSucceedsLarge() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(val) VALUES (?)")) {
      for (int i = 0; i < 2000; i++) {
        ps.setString(1, "row-" + i);
        ps.addBatch();
      }
      int[] counts = ps.executeBatch();
      assertEquals(2000, counts.length);
    }

    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery("SELECT count(*) FROM pipeline_test")) {
      assertTrue(rs.next());
      assertEquals(2000, rs.getInt(1));
    }
  }

  @Test
  void errorAtStartOfBatch() throws SQLException {
    con.setAutoCommit(false);
    try (Statement s = con.createStatement()) {
      s.execute("INSERT INTO pipeline_test(val) VALUES ('existing')");
    }
    con.commit();

    int existingId;
    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery(
            "SELECT id FROM pipeline_test WHERE val = 'existing'")) {
      assertTrue(rs.next());
      existingId = rs.getInt(1);
    }

    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(id, val) VALUES (?, ?)")) {
      // First entry: duplicate key
      ps.setInt(1, existingId);
      ps.setString(2, "dup");
      ps.addBatch();
      // Remaining entries: valid
      for (int i = 0; i < 5; i++) {
        ps.setInt(1, existingId + 1000 + i);
        ps.setString(2, "row-" + i);
        ps.addBatch();
      }

      BatchUpdateException ex = assertThrows(BatchUpdateException.class, ps::executeBatch);
      int[] counts = ex.getUpdateCounts();
      assertEquals(6, counts.length);
      for (int count : counts) {
        assertEquals(Statement.EXECUTE_FAILED, count);
      }
    }
    con.rollback();
  }

  @Test
  void errorInMiddleOfBatch() throws SQLException {
    con.setAutoCommit(false);
    try (Statement s = con.createStatement()) {
      s.execute("INSERT INTO pipeline_test(val) VALUES ('existing')");
    }
    con.commit();

    int existingId;
    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery(
            "SELECT id FROM pipeline_test WHERE val = 'existing'")) {
      assertTrue(rs.next());
      existingId = rs.getInt(1);
    }

    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(id, val) VALUES (?, ?)")) {
      // First 3 entries: valid
      for (int i = 0; i < 3; i++) {
        ps.setInt(1, existingId + 1000 + i);
        ps.setString(2, "row-" + i);
        ps.addBatch();
      }
      // Entry 4: duplicate key
      ps.setInt(1, existingId);
      ps.setString(2, "dup");
      ps.addBatch();
      // Remaining entries: would be valid but server discards them
      for (int i = 0; i < 3; i++) {
        ps.setInt(1, existingId + 2000 + i);
        ps.setString(2, "after-" + i);
        ps.addBatch();
      }

      BatchUpdateException ex = assertThrows(BatchUpdateException.class, ps::executeBatch);
      int[] counts = ex.getUpdateCounts();
      assertEquals(7, counts.length);
      // All entries marked EXECUTE_FAILED because transaction is rolled back
      for (int count : counts) {
        assertEquals(Statement.EXECUTE_FAILED, count);
      }
    }
    con.rollback();
  }

  @Test
  void errorAtEndOfBatch() throws SQLException {
    con.setAutoCommit(false);
    try (Statement s = con.createStatement()) {
      s.execute("INSERT INTO pipeline_test(val) VALUES ('existing')");
    }
    con.commit();

    int existingId;
    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery(
            "SELECT id FROM pipeline_test WHERE val = 'existing'")) {
      assertTrue(rs.next());
      existingId = rs.getInt(1);
    }

    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(id, val) VALUES (?, ?)")) {
      // First 5 entries: valid
      for (int i = 0; i < 5; i++) {
        ps.setInt(1, existingId + 1000 + i);
        ps.setString(2, "row-" + i);
        ps.addBatch();
      }
      // Last entry: duplicate key
      ps.setInt(1, existingId);
      ps.setString(2, "dup");
      ps.addBatch();

      BatchUpdateException ex = assertThrows(BatchUpdateException.class, ps::executeBatch);
      int[] counts = ex.getUpdateCounts();
      assertEquals(6, counts.length);
      for (int count : counts) {
        assertEquals(Statement.EXECUTE_FAILED, count);
      }
    }
    con.rollback();
  }

  @Test
  void parseErrorInMiddleOfBatch() throws SQLException {
    con.setAutoCommit(false);
    try (Statement s = con.createStatement()) {
      // Valid statements
      s.addBatch("INSERT INTO pipeline_test(val) VALUES ('a')");
      s.addBatch("INSERT INTO pipeline_test(val) VALUES ('b')");
      // Parse error
      s.addBatch("INVALID SQL STATEMENT");
      // Would be valid but discarded by server
      s.addBatch("INSERT INTO pipeline_test(val) VALUES ('c')");

      BatchUpdateException ex = assertThrows(BatchUpdateException.class, s::executeBatch);
      int[] counts = ex.getUpdateCounts();
      assertEquals(4, counts.length);
      for (int count : counts) {
        assertEquals(Statement.EXECUTE_FAILED, count);
      }
    }
    con.rollback();
  }

  @Test
  void autocommitBatchErrorIsAtomic() throws SQLException {
    con.setAutoCommit(true);
    try (Statement s = con.createStatement()) {
      s.execute("INSERT INTO pipeline_test(val) VALUES ('existing')");
    }

    int existingId;
    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery(
            "SELECT id FROM pipeline_test WHERE val = 'existing'")) {
      assertTrue(rs.next());
      existingId = rs.getInt(1);
    }

    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(id, val) VALUES (?, ?)")) {
      for (int i = 0; i < 5; i++) {
        ps.setInt(1, existingId + 1000 + i);
        ps.setString(2, "row-" + i);
        ps.addBatch();
      }
      // Duplicate key at end
      ps.setInt(1, existingId);
      ps.setString(2, "dup");
      ps.addBatch();

      assertThrows(BatchUpdateException.class, ps::executeBatch);
    }

    // With pipeline mode, the autocommit batch is atomic — nothing committed
    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery(
            "SELECT count(*) FROM pipeline_test WHERE val LIKE 'row-%'")) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
    }
  }

  @Test
  void generatedKeysNotReturnedOnError() throws SQLException {
    con.setAutoCommit(false);
    try (Statement s = con.createStatement()) {
      s.execute("INSERT INTO pipeline_test(val) VALUES ('existing')");
    }
    con.commit();

    int existingId;
    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery(
            "SELECT id FROM pipeline_test WHERE val = 'existing'")) {
      assertTrue(rs.next());
      existingId = rs.getInt(1);
    }

    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(id, val) VALUES (?, ?)",
        Statement.RETURN_GENERATED_KEYS)) {
      ps.setInt(1, existingId + 1000);
      ps.setString(2, "ok");
      ps.addBatch();
      // Duplicate key
      ps.setInt(1, existingId);
      ps.setString(2, "dup");
      ps.addBatch();

      assertThrows(BatchUpdateException.class, ps::executeBatch);
      ResultSet keys = ps.getGeneratedKeys();
      if (keys != null) {
        // Keys should be empty — the transaction was rolled back
        assertFalse(keys.next(), "Generated keys should be empty after batch error");
      }
    }
    con.rollback();
  }

  @Test
  void connectionUsableAfterBatchError() throws SQLException {
    con.setAutoCommit(true);
    try (Statement s = con.createStatement()) {
      s.execute("INSERT INTO pipeline_test(val) VALUES ('existing')");
    }

    try (Statement s = con.createStatement()) {
      s.addBatch("INSERT INTO pipeline_test(val) VALUES ('a')");
      s.addBatch("INVALID SQL");
      try {
        s.executeBatch();
      } catch (BatchUpdateException e) {
        // expected
      }
    }

    // Connection should still be usable
    try (Statement s = con.createStatement();
        ResultSet rs = s.executeQuery("SELECT 1")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void largeBatchDoesNotDeadlock() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(val) VALUES (?)")) {
      for (int i = 0; i < 5000; i++) {
        ps.setString(1, "row-" + i + "-padding-to-make-payload-larger-for-deadlock-test");
        ps.addBatch();
      }
      int[] counts = ps.executeBatch();
      assertEquals(5000, counts.length);
    }
  }
}
