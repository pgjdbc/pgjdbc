/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;


/**
 * Integration tests for pipeline mode (dedicated reader thread).
 * Requires a running PostgreSQL server configured via build.local.properties.
 */
@Timeout(30)
public class PipelineModeTest extends BaseTest4 {

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.PIPELINE_MODE.set(props, true);
  }

  @Test
  public void testSimpleSelect() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1 AS x, 'hello' AS y")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt("x"));
      assertEquals("hello", rs.getString("y"));
      assertFalse(rs.next());
    }
  }

  @Test
  public void testMultipleSequentialQueries() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      for (int i = 0; i < 10; i++) {
        try (ResultSet rs = stmt.executeQuery("SELECT " + i + " AS val")) {
          assertTrue(rs.next());
          assertEquals(i, rs.getInt(1));
        }
      }
    }
  }

  @Test
  public void testPreparedStatement() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_test", "id INT, name TEXT");
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_test (id, name) VALUES (?, ?)")) {
      ps.setInt(1, 1);
      ps.setString(2, "alice");
      assertEquals(1, ps.executeUpdate());

      ps.setInt(1, 2);
      ps.setString(2, "bob");
      assertEquals(1, ps.executeUpdate());
    }

    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT id, name FROM pipeline_test ORDER BY id")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals("alice", rs.getString(2));
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
      assertEquals("bob", rs.getString(2));
      assertFalse(rs.next());
    }
  }

  @Test
  public void testBatchInsert() throws SQLException {
    org.junit.jupiter.api.Assumptions.assumeFalse(
        Boolean.parseBoolean(System.getProperty("reWriteBatchedInserts")),
        "reWriteBatchedInserts returns SUCCESS_NO_INFO instead of per-row counts");
    TestUtil.createTempTable(con, "pipeline_batch", "id INT, val INT");
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_batch (id, val) VALUES (?, ?)")) {
      for (int i = 0; i < 100; i++) {
        ps.setInt(1, i);
        ps.setInt(2, i * 10);
        ps.addBatch();
      }
      int[] counts = ps.executeBatch();
      assertEquals(100, counts.length);
      for (int count : counts) {
        assertEquals(1, count);
      }
    }

    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT count(*) FROM pipeline_batch")) {
      assertTrue(rs.next());
      assertEquals(100, rs.getInt(1));
    }
  }

  @Test
  public void testCursorFetch() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_cursor", "id INT");
    try (Statement stmt = con.createStatement()) {
      StringBuilder sb = new StringBuilder("INSERT INTO pipeline_cursor VALUES ");
      for (int i = 0; i < 200; i++) {
        if (i > 0) {
          sb.append(',');
        }
        sb.append('(').append(i).append(')');
      }
      stmt.executeUpdate(sb.toString());
    }

    // Use fetchSize to trigger cursor/portal mode
    try (PreparedStatement ps = con.prepareStatement("SELECT id FROM pipeline_cursor ORDER BY id")) {
      ps.setFetchSize(10);
      try (ResultSet rs = ps.executeQuery()) {
        int count = 0;
        while (rs.next()) {
          assertEquals(count, rs.getInt(1));
          count++;
        }
        assertEquals(200, count);
      }
    }
  }

  @Test
  public void testTransactionCommit() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_tx", "id INT");
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_tx VALUES (?::int)")) {
      ps.setInt(1, 42);
      ps.executeUpdate();
    }
    con.commit();

    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT id FROM pipeline_tx")) {
      assertTrue(rs.next());
      assertEquals(42, rs.getInt(1));
    }
    con.setAutoCommit(true);
  }

  @Test
  public void testTransactionRollback() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_rb", "id INT");
    con.setAutoCommit(false);
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_rb VALUES (?::int)")) {
      ps.setInt(1, 99);
      ps.executeUpdate();
    }
    con.rollback();

    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT count(*) FROM pipeline_rb")) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
    }
    con.setAutoCommit(true);
  }

  @Test
  public void testErrorHandling() throws SQLException {
    // Query that will fail
    try (Statement stmt = con.createStatement()) {
      assertThrows(SQLException.class, () ->
          stmt.executeQuery("SELECT * FROM nonexistent_table_xyz"));
    }

    // Connection should still be usable after error
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  public void testBatchWithError() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_berr", "id INT PRIMARY KEY");
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_berr VALUES (?)")) {
      ps.setInt(1, 1);
      ps.addBatch();
      ps.setInt(1, 1); // duplicate key — will fail
      ps.addBatch();
      ps.setInt(1, 3);
      ps.addBatch();

      assertThrows(SQLException.class, ps::executeBatch);
    }

    // Connection should still be usable
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {
      assertTrue(rs.next());
    }
  }

  @Test
  public void testLargeResultSet() throws SQLException {
    // Test with a generated series — no temp table needed
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT generate_series(1, 10000) AS n")) {
      int count = 0;
      while (rs.next()) {
        count++;
        assertEquals(count, rs.getInt(1));
      }
      assertEquals(10000, count);
    }
  }

  @Test
  public void testNullValues() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT NULL::text, NULL::int, 'x'::text")) {
      assertTrue(rs.next());
      assertNull(rs.getString(1));
      assertEquals(0, rs.getInt(2));
      assertTrue(rs.wasNull());
      assertEquals("x", rs.getString(3));
    }
  }

  @Test
  public void testMultipleResultColumns() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery(
             "SELECT 1::int, 2::bigint, 3.14::float, true::bool, 'test'::text")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
      assertEquals(2L, rs.getLong(2));
      assertEquals(3.14, rs.getDouble(3), 0.001);
      assertTrue(rs.getBoolean(4));
      assertEquals("test", rs.getString(5));
    }
  }

  @Test
  public void testPreparedStatementReuse() throws SQLException {
    // Tests that prepared statement caching works with pipeline mode
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::int + 1")) {
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
  public void testConnectionMetadata() throws SQLException {
    // Verify connection is functional for metadata operations
    assertNotNull(con.getMetaData());
    assertNotNull(con.getMetaData().getDatabaseProductName());
    assertTrue(con.getMetaData().getDatabaseProductName().contains("PostgreSQL"));
  }

  @Test
  public void testAutoCommitDDL() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_ddl", "id SERIAL PRIMARY KEY, data TEXT");
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO pipeline_ddl (data) VALUES (?) RETURNING id")) {
      ps.setString(1, "test");
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertTrue(rs.getInt(1) > 0);
      }
    }
  }

  @Test
  public void testSyntaxError() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      SQLException ex = assertThrows(SQLException.class, () ->
          stmt.executeQuery("SELEKT 1"));
      // Should be a syntax error
      assertTrue(ex.getSQLState().startsWith("42"),
          "Expected syntax error SQLState 42xxx, got: " + ex.getSQLState());
      assertTrue(ex.getMessage().contains("syntax"),
          "Expected 'syntax' in message, got: " + ex.getMessage());
    }

    // Connection must remain usable
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 42")) {
      assertTrue(rs.next());
      assertEquals(42, rs.getInt(1));
    }
  }

  @Test
  public void testDivisionByZero() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      SQLException ex = assertThrows(SQLException.class, () ->
          stmt.executeQuery("SELECT 1/0"));
      assertEquals("22012", ex.getSQLState(), "Expected division_by_zero SQLState");
    }

    // Connection must remain usable
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 99")) {
      assertTrue(rs.next());
      assertEquals(99, rs.getInt(1));
    }
  }

  @Test
  public void testUniqueViolation() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_uniq", "id INT PRIMARY KEY");
    try (Statement stmt = con.createStatement()) {
      stmt.executeUpdate("INSERT INTO pipeline_uniq VALUES (1)");

      SQLException ex = assertThrows(SQLException.class, () ->
          stmt.executeUpdate("INSERT INTO pipeline_uniq VALUES (1)"));
      assertEquals("23505", ex.getSQLState(), "Expected unique_violation SQLState");
    }

    // Connection must remain usable
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT count(*) FROM pipeline_uniq")) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  public void testErrorInTransaction() throws SQLException {
    TestUtil.createTempTable(con, "pipeline_txerr", "id INT PRIMARY KEY");
    con.setAutoCommit(false);

    try (Statement stmt = con.createStatement()) {
      stmt.executeUpdate("INSERT INTO pipeline_txerr VALUES (1)");

      // This will fail — puts transaction in aborted state
      assertThrows(SQLException.class, () ->
          stmt.executeUpdate("INSERT INTO pipeline_txerr VALUES (1)"));

      // Any subsequent query in the aborted transaction should fail
      SQLException ex = assertThrows(SQLException.class, () ->
          stmt.executeQuery("SELECT 1"));
      assertEquals("25P02", ex.getSQLState(),
          "Expected in_failed_sql_transaction SQLState");
    }

    // Rollback should recover the connection
    con.rollback();

    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT count(*) FROM pipeline_txerr")) {
      assertTrue(rs.next());
      assertEquals(0, rs.getInt(1));
    }
    con.setAutoCommit(true);
  }

  @Test
  public void testPreparedStatementWithWrongParamCount() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT ?::int, ?::int")) {
      ps.setInt(1, 1);
      // Missing parameter 2
      assertThrows(SQLException.class, ps::executeQuery);
    }

    // Connection must remain usable
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {
      assertTrue(rs.next());
    }
  }

  @Test
  public void testErrorRecoveryMultipleQueries() throws SQLException {
    // Run several queries, inject an error, then verify recovery with more queries
    try (Statement stmt = con.createStatement()) {
      // Successful queries before error
      for (int i = 0; i < 5; i++) {
        try (ResultSet rs = stmt.executeQuery("SELECT " + i)) {
          assertTrue(rs.next());
          assertEquals(i, rs.getInt(1));
        }
      }

      // Error
      assertThrows(SQLException.class, () ->
          stmt.executeQuery("SELECT * FROM this_table_does_not_exist"));

      // Successful queries after error
      for (int i = 10; i < 15; i++) {
        try (ResultSet rs = stmt.executeQuery("SELECT " + i)) {
          assertTrue(rs.next());
          assertEquals(i, rs.getInt(1));
        }
      }
    }
  }

  @Test
  public void testRaiseException() throws SQLException {
    // Use DO block to raise a custom exception
    try (Statement stmt = con.createStatement()) {
      SQLException ex = assertThrows(SQLException.class, () ->
          stmt.execute("DO $$ BEGIN RAISE EXCEPTION 'injected error' USING ERRCODE = 'P0001'; END $$"));
      assertEquals("P0001", ex.getSQLState(), "Expected raise_exception SQLState");
      assertTrue(ex.getMessage().contains("injected error"),
          "Expected 'injected error' in message, got: " + ex.getMessage());
    }

    // Connection must remain usable
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 'recovered'")) {
      assertTrue(rs.next());
      assertEquals("recovered", rs.getString(1));
    }
  }
}
