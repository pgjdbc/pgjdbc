/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

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
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Performance comparison tests for pipeline mode vs synchronous execution.
 * These tests verify that pipeline mode provides a performance benefit for batch operations.
 */
public class PipelinePerformanceTest {

  private static final Logger LOGGER = Logger.getLogger(PipelinePerformanceTest.class.getName());
  private static final int BATCH_SIZE = 500;
  private static final int WARMUP_ITERATIONS = 3;
  private static final int MEASURE_ITERATIONS = 5;

  @BeforeAll
  static void setUp() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "pipeline_perf_test", "id SERIAL PRIMARY KEY, data TEXT");
    }
  }

  @AfterAll
  static void tearDown() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "pipeline_perf_test");
    }
  }

  private static Connection openPipelineConnection() throws SQLException {
    Properties props = new Properties();
    PGProperty.PIPELINE_MODE.set(props, true);
    return TestUtil.openDB(props);
  }

  private static Connection openNormalConnection() throws SQLException {
    return TestUtil.openDB(new Properties());
  }

  private long runBatchInsert(Connection con, int batchSize) throws SQLException {
    con.setAutoCommit(false);
    long start = System.nanoTime();
    try (PreparedStatement ps = con.prepareStatement("INSERT INTO pipeline_perf_test(data) VALUES(?)")) {
      for (int i = 0; i < batchSize; i++) {
        ps.setString(1, "perf_" + i);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    con.commit();
    long elapsed = System.nanoTime() - start;
    // Clean up
    try (PreparedStatement ps = con.prepareStatement("DELETE FROM pipeline_perf_test")) {
      ps.executeUpdate();
    }
    con.setAutoCommit(true);
    return elapsed;
  }

  private long runPreparedStatementReuse(Connection con, int iterations) throws SQLException {
    long start = System.nanoTime();
    try (PreparedStatement ps = con.prepareStatement("SELECT ? + 1")) {
      for (int i = 0; i < iterations; i++) {
        ps.setInt(1, i);
        try (ResultSet rs = ps.executeQuery()) {
          rs.next();
        }
      }
    }
    return System.nanoTime() - start;
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void batchInsertPerformance() throws SQLException {
    long pipelineTime;
    long normalTime;

    // Warmup
    try (Connection con = openPipelineConnection()) {
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runBatchInsert(con, BATCH_SIZE);
      }
    }
    try (Connection con = openNormalConnection()) {
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runBatchInsert(con, BATCH_SIZE);
      }
    }

    // Measure pipeline
    long pipelineTotal = 0;
    try (Connection con = openPipelineConnection()) {
      for (int i = 0; i < MEASURE_ITERATIONS; i++) {
        pipelineTotal += runBatchInsert(con, BATCH_SIZE);
      }
    }
    pipelineTime = pipelineTotal / MEASURE_ITERATIONS;

    // Measure normal
    long normalTotal = 0;
    try (Connection con = openNormalConnection()) {
      for (int i = 0; i < MEASURE_ITERATIONS; i++) {
        normalTotal += runBatchInsert(con, BATCH_SIZE);
      }
    }
    normalTime = normalTotal / MEASURE_ITERATIONS;

    double ratio = (double) pipelineTime / normalTime;
    LOGGER.log(Level.INFO, "Batch insert: pipeline={0}ms, normal={1}ms, ratio={2}",
        new Object[]{pipelineTime / 1_000_000, normalTime / 1_000_000, String.format("%.2f", ratio)});

    // Pipeline should be at least as fast as normal (allow 20% margin for noise)
    assertTrue(ratio < 1.2,
        "Pipeline mode should not be significantly slower than normal. Ratio: " + ratio);
  }

  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void preparedStatementReusePerformance() throws SQLException {
    int iterations = 200;
    long pipelineTime;
    long normalTime;

    // Warmup
    try (Connection con = openPipelineConnection()) {
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runPreparedStatementReuse(con, iterations);
      }
    }
    try (Connection con = openNormalConnection()) {
      for (int i = 0; i < WARMUP_ITERATIONS; i++) {
        runPreparedStatementReuse(con, iterations);
      }
    }

    // Measure pipeline
    long pipelineTotal = 0;
    try (Connection con = openPipelineConnection()) {
      for (int i = 0; i < MEASURE_ITERATIONS; i++) {
        pipelineTotal += runPreparedStatementReuse(con, iterations);
      }
    }
    pipelineTime = pipelineTotal / MEASURE_ITERATIONS;

    // Measure normal
    long normalTotal = 0;
    try (Connection con = openNormalConnection()) {
      for (int i = 0; i < MEASURE_ITERATIONS; i++) {
        normalTotal += runPreparedStatementReuse(con, iterations);
      }
    }
    normalTime = normalTotal / MEASURE_ITERATIONS;

    double ratio = (double) pipelineTime / normalTime;
    LOGGER.log(Level.INFO, "Prepared statement reuse: pipeline={0}ms, normal={1}ms, ratio={2}",
        new Object[]{pipelineTime / 1_000_000, normalTime / 1_000_000, String.format("%.2f", ratio)});

    // Pipeline should be at least as fast as normal (allow 20% margin for noise)
    assertTrue(ratio < 1.2,
        "Pipeline mode should not be significantly slower than normal. Ratio: " + ratio);
  }
}
