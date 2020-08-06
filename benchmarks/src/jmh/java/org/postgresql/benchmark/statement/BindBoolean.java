/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.statement;

import org.postgresql.benchmark.profilers.FlightRecorderProfiler;
import org.postgresql.util.ConnectionUtil;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BindBoolean {
  private Connection connection;
  private PreparedStatement ps;

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = ConnectionUtil.getProperties();

    connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    ps = connection.prepareStatement("select ?");
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    ps.close();
    connection.close();
  }

  @Benchmark
  public Statement boolAsInt() throws SQLException {
    ps.setObject(1, 1, Types.BOOLEAN);
    return ps;
  }

  @Benchmark
  public Statement boolAsBoolean() throws SQLException {
    ps.setObject(1, true, Types.BOOLEAN);
    return ps;
  }

  @Benchmark
  public Statement bindBoolean() throws SQLException {
    ps.setBoolean(1, true);
    return ps;
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(BindBoolean.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .addProfiler(FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }
}
