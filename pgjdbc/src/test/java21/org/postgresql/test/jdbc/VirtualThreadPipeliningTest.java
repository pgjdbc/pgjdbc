/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Virtual thread pipelining tests.
 */
public class VirtualThreadPipeliningTest extends BaseTest4 {

  @Test
  public void testPipelining() throws Exception {
    // Test 1: Measure with pipeline mode enabled
    Properties propsWithPipeline = new Properties();
    propsWithPipeline.setProperty("pipelineMode", "true");
    propsWithPipeline.setProperty("sslmode", "disable");

    long pipelinedTime;
    try (Connection pipelineCon = TestUtil.openDB(propsWithPipeline)) {
      pipelinedTime = measureQueryTime(pipelineCon);
    }

    // Test 2: Measure without pipeline mode (baseline)
    Properties propsNoPipeline = new Properties();
    propsNoPipeline.setProperty("pipelineMode", "false");
    propsNoPipeline.setProperty("sslmode", "disable");

    long sequentialTime;
    try (Connection seqCon = TestUtil.openDB(propsNoPipeline)) {
      sequentialTime = measureQueryTime(seqCon);
    }

    // Pipelined should be significantly faster (at least 30% faster)
    assertTrue(pipelinedTime < sequentialTime * 0.7,
        String.format("Pipeline mode (%dms) should be faster than sequential (%dms)",
            pipelinedTime, sequentialTime));
  }

  private long measureQueryTime(Connection conn) throws Exception {
    // Use many small, fast queries instead of pg_sleep
    class FastQuery implements Callable<Void> {
      private final Connection connection;

      FastQuery(Connection connection) {
        this.connection = connection;
      }

      @Override
      public Void call() throws Exception {
        try (Statement stmt = connection.createStatement()) {
          // Execute 1000 fast queries
          for (int i = 0; i < 1000; i++) {
            try (ResultSet rs = stmt.executeQuery("SELECT 1")) {
              rs.next();
            }
          }
        }
        return null;
      }
    }

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Instant start = Instant.now();

      // Launch 3 virtual threads executing queries concurrently
      Future<Void> f1 = executor.submit(new FastQuery(conn));
      Future<Void> f2 = executor.submit(new FastQuery(conn));
      Future<Void> f3 = executor.submit(new FastQuery(conn));

      f1.get();
      f2.get();
      f3.get();

      return Duration.between(start, Instant.now()).toMillis();
    }
  }

}
