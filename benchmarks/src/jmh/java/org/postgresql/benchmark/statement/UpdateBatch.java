/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.statement;

import org.postgresql.util.ConnectionUtil;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class UpdateBatch {
  private Connection connection;
  private PreparedStatement ps;

  @Param({"100"})
  int nrows;

  @Setup(Level.Trial)
  public void setUp(BenchmarkParams bp) throws SQLException {
    Properties props = ConnectionUtil.getProperties();
    connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    Statement s = connection.createStatement();

    try {
      s.execute("drop table batch_perf_test");
    } catch (SQLException e) {
      /* ignore */
    }
    s.execute("create table batch_perf_test(a int4, b varchar(100), c int4)");
    s.close();
    ps = connection.prepareStatement("insert into batch_perf_test(a) select 42 where false");
  }

  @Benchmark
  public int[] updateBatch() throws SQLException {
    for (int i = 0; i < nrows; i++) {
      ps.addBatch();
    }
    return ps.executeBatch();
  }

  public static void main(String[] args) throws RunnerException {
    //DriverManager.setLogWriter(new PrintWriter(System.out));
    //Driver.setLogLevel(2);
    Options opt = new OptionsBuilder()
        .include(UpdateBatch.class.getSimpleName())
        .addProfiler(org.openjdk.jmh.profile.GCProfiler.class)
        .addProfiler(org.postgresql.benchmark.profilers.FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }

}
