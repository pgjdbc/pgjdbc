/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.metadata;

import org.postgresql.test.TestUtil;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;

@Fork(value = 3, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class DatabaseMetadataBenchmark {
  int index;

  private Connection connection;

  @Setup
  public void setup() throws SQLException {
    connection = TestUtil.openDB();
    // See DatabaseMetaDataTest
    TestUtil.createTable(connection, "vv", "a int not null, b int not null, constraint vv_pkey primary key ( a, b )");
    TestUtil.createTable(connection, "ww",
        "m int not null, n int not null, constraint m_pkey primary key ( m, n ), constraint ww_m_fkey foreign key ( m, n ) references vv ( a, b )");
  }

  @Benchmark
  public void getCrossReferences(Blackhole blackhole) throws SQLException {
    index++;
    try (ResultSet rs = connection.getMetaData().getCrossReference("", "", "vv" + index, null, null, "ww" + index); ) {
      while (rs.next()) {
        blackhole.consume(rs.getString("PK_NAME"));
      }
    }
  }

  @Benchmark
  public void getTables(Blackhole blackhole) throws SQLException {
    index++;
    try (ResultSet rs = connection.getMetaData().getTables(null, null, "tab" + index, null); ) {
      while (rs.next()) {
        blackhole.consume(rs.getString("TABLE_NAME"));
      }
    }
  }

  @Benchmark
  public void getColumns_all(Blackhole blackhole) throws SQLException {
    index++;
    try (ResultSet rs = connection.getMetaData().getColumns(null, null, "tab" + index, null); ) {
      while (rs.next()) {
        blackhole.consume(rs.getString("TABLE_NAME"));
      }
    }
  }

  @Benchmark
  public void getColumns_named(Blackhole blackhole) throws SQLException {
    index++;
    try (ResultSet rs = connection.getMetaData().getColumns(null, null, "tab" + index, "col" + index); ) {
      while (rs.next()) {
        blackhole.consume(rs.getString("TABLE_NAME"));
      }
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(DatabaseMetadataBenchmark.class.getSimpleName())
        // .addProfiler("gc", "churn=false")
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
