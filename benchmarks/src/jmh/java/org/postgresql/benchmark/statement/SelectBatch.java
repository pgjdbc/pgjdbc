/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.statement;

import org.postgresql.PGConnection;
import org.postgresql.benchmark.profilers.FlightRecorderProfiler;
import org.postgresql.test.TestUtil;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Warmup(iterations = 10, time = 5, timeUnit = TimeUnit.MINUTES)
@Measurement(iterations = 10, time = 30, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
public class SelectBatch {
  @Param({ "5", "1" })
  private int prepareThreshold;

  private Connection connection;
  private String simpleSql;
  private String[] compositeSqls;

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    connection = TestUtil.openDB();
    ((PGConnection) connection).setPrepareThreshold(prepareThreshold);
    try (Statement st = connection.createStatement()) {
      st.execute("DROP TABLE IF EXISTS bench_select");
      st.execute("CREATE TABLE bench_select (id INT PRIMARY KEY, val TEXT)");
    }
    try (PreparedStatement ins = connection.prepareStatement(
        "INSERT INTO bench_select(id,val) VALUES (?,?)")) {
      for (int i = 1; i <= 1000; i++) {
        ins.setInt(1, i);
        ins.setString(2, "строка_" + i);
        ins.addBatch();
      }
      ins.executeBatch();
    }
    simpleSql = "SELECT val FROM bench_select WHERE id = ?;";
    compositeSqls = new String[1000];
    for (int len = 1; len <= 1000; len++) {
      StringBuilder sb = new StringBuilder();
      for (int j = 0; j < len; j++) {
        sb.append(simpleSql);
      }
      compositeSqls[len - 1] = sb.toString();
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    Statement s = connection.createStatement();
    s.execute("DROP TABLE IF EXISTS bench_select");
    s.close();
    connection.close();
  }

  @Benchmark
  public void benchSimple(Blackhole bh) throws SQLException {
    try (PreparedStatement ps = connection.prepareStatement(simpleSql)) {
      ps.setInt(1, 1);
      boolean hasResult = ps.execute();
      bh.consume(hasResult);
      if (hasResult) {
        ResultSet rs = ps.getResultSet();
        while (rs.next()) {
          String value = rs.getString(1);
          bh.consume(value);
        }
      }
    }
  }

  @Benchmark
  @OperationsPerInvocation(500500)
  public void benchComposite(Blackhole bh) throws SQLException {
    for (int i = 0; i < compositeSqls.length; i++) {
      try (PreparedStatement ps = connection.prepareStatement(compositeSqls[i])) {
        for (int p = 1; p <= i + 1; p++) {
          ps.setInt(p, p);
        }
        boolean hasResult = ps.execute();
        bh.consume(hasResult);
        while (hasResult) {
          ResultSet rs = ps.getResultSet();
          while (rs.next()) {
            bh.consume(rs.getString(1));
          }
          hasResult = ps.getMoreResults();
          bh.consume(hasResult);
        }
      }
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(SelectBatch.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .addProfiler(FlightRecorderProfiler.class)
        .build();
    new Runner(opt).run();
  }
}
