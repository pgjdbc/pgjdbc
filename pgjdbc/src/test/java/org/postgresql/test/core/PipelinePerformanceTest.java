/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Comparative performance test: pipeline mode vs synchronous mode.
 * Not a JMH benchmark — just a quick A/B comparison to validate
 * that pipeline mode is at least as fast as synchronous mode.
 *
 * <p>Run with: ./gradlew test --tests "org.postgresql.test.core.PipelinePerformanceTest"
 */
public class PipelinePerformanceTest {

  private static Connection syncConn;
  private static Connection pipeConn;

  @BeforeAll
  static void setUp() throws SQLException {
    Properties syncProps = new Properties();
    syncConn = TestUtil.openDB(syncProps);

    Properties pipeProps = new Properties();
    PGProperty.PIPELINE_MODE.set(pipeProps, true);
    pipeConn = TestUtil.openDB(pipeProps);

    try (Statement stmt = syncConn.createStatement()) {
      try {
        stmt.execute("DROP TABLE IF EXISTS perf_test");
      } catch (SQLException e) {
        /* ignore */
      }
      stmt.execute("CREATE TABLE perf_test (id INT, val TEXT)");
    }
  }

  @AfterAll
  static void tearDown() throws SQLException {
    if (syncConn != null) {
      try (Statement stmt = syncConn.createStatement()) {
        stmt.execute("DROP TABLE IF EXISTS perf_test");
      }
      syncConn.close();
    }
    if (pipeConn != null) {
      pipeConn.close();
    }
  }

  @Test
  public void compareSequentialQueries() throws SQLException {
    int iterations = 200;

    // Warmup both
    runSequentialQueries(syncConn, 50);
    runSequentialQueries(pipeConn, 50);

    // Measure synchronous
    long syncStart = System.nanoTime();
    runSequentialQueries(syncConn, iterations);
    long syncElapsed = System.nanoTime() - syncStart;

    // Measure pipeline
    long pipeStart = System.nanoTime();
    runSequentialQueries(pipeConn, iterations);
    long pipeElapsed = System.nanoTime() - pipeStart;

    double syncMs = syncElapsed / 1_000_000.0;
    double pipeMs = pipeElapsed / 1_000_000.0;
    double ratio = syncMs / pipeMs;

    System.out.printf("%n=== Sequential Queries (%d iterations) ===%n", iterations);
    System.out.printf("  Synchronous: %.2f ms%n", syncMs);
    System.out.printf("  Pipeline:    %.2f ms%n", pipeMs);
    System.out.printf("  Ratio:       %.2fx %s%n", ratio,
        ratio > 1 ? "(pipeline faster)" : "(sync faster)");
  }

  @Test
  public void compareBatchInsert() throws SQLException {
    int batchSize = 500;

    // Warmup
    runBatchInsert(syncConn, 100);
    runBatchInsert(pipeConn, 100);

    // Measure synchronous
    long syncStart = System.nanoTime();
    runBatchInsert(syncConn, batchSize);
    long syncElapsed = System.nanoTime() - syncStart;

    // Measure pipeline
    long pipeStart = System.nanoTime();
    runBatchInsert(pipeConn, batchSize);
    long pipeElapsed = System.nanoTime() - pipeStart;

    double syncMs = syncElapsed / 1_000_000.0;
    double pipeMs = pipeElapsed / 1_000_000.0;
    double ratio = syncMs / pipeMs;

    System.out.printf("%n=== Batch Insert (%d rows) ===%n", batchSize);
    System.out.printf("  Synchronous: %.2f ms%n", syncMs);
    System.out.printf("  Pipeline:    %.2f ms%n", pipeMs);
    System.out.printf("  Ratio:       %.2fx %s%n", ratio,
        ratio > 1 ? "(pipeline faster)" : "(sync faster)");
  }

  @Test
  public void comparePreparedStatementReuse() throws SQLException {
    int iterations = 500;

    // Warmup
    runPreparedReuse(syncConn, 100);
    runPreparedReuse(pipeConn, 100);

    // Measure synchronous
    long syncStart = System.nanoTime();
    runPreparedReuse(syncConn, iterations);
    long syncElapsed = System.nanoTime() - syncStart;

    // Measure pipeline
    long pipeStart = System.nanoTime();
    runPreparedReuse(pipeConn, iterations);
    long pipeElapsed = System.nanoTime() - pipeStart;

    double syncMs = syncElapsed / 1_000_000.0;
    double pipeMs = pipeElapsed / 1_000_000.0;
    double ratio = syncMs / pipeMs;

    System.out.printf("%n=== Prepared Statement Reuse (%d iterations) ===%n", iterations);
    System.out.printf("  Synchronous: %.2f ms%n", syncMs);
    System.out.printf("  Pipeline:    %.2f ms%n", pipeMs);
    System.out.printf("  Ratio:       %.2fx %s%n", ratio,
        ratio > 1 ? "(pipeline faster)" : "(sync faster)");
  }

  @Test
  public void compareLargeResultFetch() throws SQLException {
    int rows = 10000;

    // Warmup
    runLargeResultFetch(syncConn, 1000);
    runLargeResultFetch(pipeConn, 1000);

    // Measure synchronous
    long syncStart = System.nanoTime();
    runLargeResultFetch(syncConn, rows);
    long syncElapsed = System.nanoTime() - syncStart;

    // Measure pipeline
    long pipeStart = System.nanoTime();
    runLargeResultFetch(pipeConn, rows);
    long pipeElapsed = System.nanoTime() - pipeStart;

    double syncMs = syncElapsed / 1_000_000.0;
    double pipeMs = pipeElapsed / 1_000_000.0;
    double ratio = syncMs / pipeMs;

    System.out.printf("%n=== Large Result Fetch (%d rows) ===%n", rows);
    System.out.printf("  Synchronous: %.2f ms%n", syncMs);
    System.out.printf("  Pipeline:    %.2f ms%n", pipeMs);
    System.out.printf("  Ratio:       %.2fx %s%n", ratio,
        ratio > 1 ? "(pipeline faster)" : "(sync faster)");
  }

  private void runSequentialQueries(Connection conn, int n) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement("SELECT ?::int + 1")) {
      for (int i = 0; i < n; i++) {
        ps.setInt(1, i);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          rs.getInt(1);
        }
      }
    }
  }

  private void runBatchInsert(Connection conn, int n) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("TRUNCATE perf_test");
    }
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO perf_test (id, val) VALUES (?, ?)")) {
      for (int i = 0; i < n; i++) {
        ps.setInt(1, i);
        ps.setString(2, "value_" + i);
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private void runPreparedReuse(Connection conn, int n) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ?::int, ?::text, ?::float8")) {
      for (int i = 0; i < n; i++) {
        ps.setInt(1, i);
        ps.setString(2, "test");
        ps.setDouble(3, i * 1.5);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
          rs.getInt(1);
          rs.getString(2);
          rs.getDouble(3);
        }
      }
    }
  }

  private void runLargeResultFetch(Connection conn, int rows) throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT generate_series(1, ?::int)")) {
      ps.setInt(1, rows);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rs.getInt(1);
        }
      }
    }
  }
}
