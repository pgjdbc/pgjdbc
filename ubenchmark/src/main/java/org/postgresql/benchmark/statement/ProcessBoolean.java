/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Benchmark to test performance of ResultSet.getBoolean() method.
 *
 * @author jorsol
 */
@Fork(value = 1, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class ProcessBoolean {

  private Connection connection;
  private PreparedStatement ps;
  private ResultSet rs;

  @Param({"5", "10", "50", "100", "10000"})
  public int rowsize;

  @Param({"0", "10", "50", "100"})
  private int tokens;

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = ConnectionUtil.getProperties();

    connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    ps = connection.prepareStatement("select (random() > 0.5) from generate_series(1, ?)",
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);
    ps.setInt(1, rowsize);
    rs = ps.executeQuery();
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    rs.close();
    ps.close();
    connection.close();
  }

  @Benchmark
  public void getBoolean(Blackhole b) throws SQLException {
    Blackhole.consumeCPU(tokens);
    rs.first();
    while (rs.next()) {
      b.consume(rs.getBoolean(1));
    }
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(ProcessBoolean.class.getSimpleName())
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
