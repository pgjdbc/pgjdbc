/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Tests for pipeline mode (synchronous pipelining without a reader thread).
 * Pipeline mode sends multiple queries before reading responses to reduce round-trip latency.
 */
public class PipelineModeTest {

  private static Connection con;

  @BeforeAll
  static void setUp() throws Exception {
    Properties props = new Properties();
    PGProperty.PIPELINE_MODE.set(props, true);
    con = TestUtil.openDB(props);
    TestUtil.createTable(con, "pipeline_test", "id SERIAL PRIMARY KEY, data TEXT");
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (con != null) {
      TestUtil.dropTable(con, "pipeline_test");
      TestUtil.closeDB(con);
    }
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void singleInsert() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_test(data) VALUES(?)")) {
      ps.setString(1, "hello");
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void singleSelect() throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("INSERT INTO pipeline_test(data) VALUES('select_test')");
    }
    try (PreparedStatement ps = con.prepareStatement("SELECT data FROM pipeline_test WHERE data = ?")) {
      ps.setString(1, "select_test");
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("select_test", rs.getString(1));
      }
    }
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void batchInsert() throws SQLException {
    int batchSize = 100;
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_test(data) VALUES(?)")) {
      for (int i = 0; i < batchSize; i++) {
        ps.setString(1, "batch_" + i);
        ps.addBatch();
      }
      int[] results = ps.executeBatch();
      assertEquals(batchSize, results.length);
      for (int r : results) {
        assertEquals(1, r);
      }
    }
    con.commit();
    con.setAutoCommit(true);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void largeBatchInsert() throws SQLException {
    int batchSize = 1000;
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_test(data) VALUES(?)")) {
      for (int i = 0; i < batchSize; i++) {
        ps.setString(1, "large_batch_" + i);
        ps.addBatch();
      }
      int[] results = ps.executeBatch();
      assertEquals(batchSize, results.length);
    }
    con.commit();
    con.setAutoCommit(true);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void batchInsertWithReturning() throws SQLException {
    int batchSize = 50;
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test(data) VALUES(?)", Statement.RETURN_GENERATED_KEYS)) {
      for (int i = 0; i < batchSize; i++) {
        ps.setString(1, "returning_" + i);
        ps.addBatch();
      }
      int[] results = ps.executeBatch();
      assertEquals(batchSize, results.length);
    }
    con.commit();
    con.setAutoCommit(true);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void preparedStatementReuse() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT ? + 1")) {
      for (int i = 0; i < 20; i++) {
        ps.setInt(1, i);
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(i + 1, rs.getInt(1));
        }
      }
    }
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void multiStatementSimpleQuery() throws SQLException {
    // Multi-statement queries use simple protocol, should still work in pipeline mode
    try (Statement st = con.createStatement()) {
      st.execute("INSERT INTO pipeline_test(data) VALUES('multi1'); INSERT INTO pipeline_test(data) VALUES('multi2')");
    }
    try (Statement st = con.createStatement()) {
      try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pipeline_test WHERE data LIKE 'multi%'")) {
        assertTrue(rs.next());
        assertTrue(rs.getInt(1) >= 2);
      }
    }
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void errorHandlingInBatch() throws SQLException {
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_test(id, data) VALUES(?, ?)")) {
      ps.setInt(1, -1);
      ps.setString(2, "first");
      ps.addBatch();
      // Duplicate key - will cause error
      ps.setInt(1, -1);
      ps.setString(2, "duplicate");
      ps.addBatch();
      try {
        ps.executeBatch();
      } catch (SQLException e) {
        // Expected - duplicate key
      }
    }
    con.rollback();
    con.setAutoCommit(true);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void cursorFetch() throws SQLException {
    // Insert some data first
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_test(data) VALUES(?)")) {
      for (int i = 0; i < 100; i++) {
        ps.setString(1, "cursor_" + i);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    con.commit();

    // Now fetch with cursor
    try (PreparedStatement ps = con.prepareStatement("SELECT data FROM pipeline_test WHERE data LIKE 'cursor_%'")) {
      ps.setFetchSize(10);
      try (ResultSet rs = ps.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          assertNotNull(rs.getString(1));
          count++;
        }
        assertEquals(100, count);
      }
    }
    con.setAutoCommit(true);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void transactionRollback() throws SQLException {
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_test(data) VALUES(?)")) {
      ps.setString(1, "rollback_test");
      ps.executeUpdate();
    }
    con.rollback();

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM pipeline_test WHERE data = 'rollback_test'")) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
    }
    con.setAutoCommit(true);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void mixedBatchStatements() throws SQLException {
    con.setAutoCommit(false);
    try (Statement st = con.createStatement()) {
      st.addBatch("INSERT INTO pipeline_test(data) VALUES('stmt_batch_1')");
      st.addBatch("INSERT INTO pipeline_test(data) VALUES('stmt_batch_2')");
      st.addBatch("INSERT INTO pipeline_test(data) VALUES('stmt_batch_3')");
      int[] results = st.executeBatch();
      assertEquals(3, results.length);
      assertArrayEquals(new int[]{1, 1, 1}, results);
    }
    con.commit();
    con.setAutoCommit(true);
  }

  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  void updateAndDelete() throws SQLException {
    try (Statement st = con.createStatement()) {
      st.execute("INSERT INTO pipeline_test(data) VALUES('update_me')");
    }
    try (PreparedStatement ps = con.prepareStatement("UPDATE pipeline_test SET data = ? WHERE data = ?")) {
      ps.setString(1, "updated");
      ps.setString(2, "update_me");
      assertEquals(1, ps.executeUpdate());
    }
    try (PreparedStatement ps = con.prepareStatement("DELETE FROM pipeline_test WHERE data = ?")) {
      ps.setString(1, "updated");
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void largeBatchWithLargePayload() throws SQLException {
    // Test that pipeline mode handles large batches with large payloads without deadlock
    int batchSize = 200;
    String payload = String.join("", java.util.Collections.nCopies(1000, "X"));
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_test(data) VALUES(?)")) {
      for (int i = 0; i < batchSize; i++) {
        ps.setString(1, payload + "_" + i);
        ps.addBatch();
      }
      int[] results = ps.executeBatch();
      assertEquals(batchSize, results.length);
    }
    con.commit();
    con.setAutoCommit(true);
  }
}
