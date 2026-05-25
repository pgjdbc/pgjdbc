/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.statement;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Compares performance of sync vs async protocol message reading for
 * batch inserts and large result set fetches.
 */
@Fork(value = 3, jvmArgsPrepend = "-Xmx256m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class AsyncReadingBenchmark {

  @Param({"true", "false"})
  String asyncReading;

  @Param({"1000", "10000"})
  int rows;

  private Connection connection;

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.ASYNC_READING.getName(), asyncReading);
    connection = TestUtil.openDB(props);

    try (Statement s = connection.createStatement()) {
      s.execute("DROP TABLE IF EXISTS async_bench_test");
      s.execute("CREATE TABLE async_bench_test("
          + "id serial PRIMARY KEY, "
          + "val1 int, "
          + "val2 varchar(100), "
          + "val3 double precision)");
      // Pre-populate for SELECT benchmarks
      s.execute("INSERT INTO async_bench_test(val1, val2, val3) "
          + "SELECT g, 'row-' || g, g * 1.1 "
          + "FROM generate_series(1, 10000) g");
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    try (Statement s = connection.createStatement()) {
      s.execute("DROP TABLE IF EXISTS async_bench_test");
    }
    connection.close();
  }

  @Benchmark
  public int[] batchInsert() throws SQLException {
    try (Statement s = connection.createStatement()) {
      s.execute("TRUNCATE async_bench_test");
    }
    try (PreparedStatement ps = connection.prepareStatement(
        "INSERT INTO async_bench_test(val1, val2, val3) VALUES (?, ?, ?)")) {
      for (int i = 0; i < rows; i++) {
        ps.setInt(1, i);
        ps.setString(2, "row-" + i);
        ps.setDouble(3, i * 1.1);
        ps.addBatch();
      }
      return ps.executeBatch();
    }
  }

  @Benchmark
  public void selectLargeResultSet(Blackhole bh) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT * FROM async_bench_test LIMIT ?")) {
      ps.setInt(1, rows);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          bh.consume(rs.getInt(1));
          bh.consume(rs.getInt(2));
          bh.consume(rs.getString(3));
          bh.consume(rs.getDouble(4));
        }
      }
    }
  }

  @Benchmark
  public void selectWithFetchSize(Blackhole bh) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(
        "SELECT * FROM async_bench_test LIMIT ?")) {
      ps.setFetchSize(100);
      ps.setInt(1, rows);
      connection.setAutoCommit(false);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          bh.consume(rs.getInt(1));
          bh.consume(rs.getInt(2));
          bh.consume(rs.getString(3));
          bh.consume(rs.getDouble(4));
        }
      } finally {
        connection.setAutoCommit(true);
      }
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(AsyncReadingBenchmark.class.getSimpleName())
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
