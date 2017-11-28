/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.connection;

import org.postgresql.PGProperty;
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
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Fork(value = 1, jvmArgsPrepend = "-Xmx64m")
@Measurement(iterations = 50, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class TransactionModeCache {

  private Connection connection;

  @Param({"false", "true"})
  public String enableCache;

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = ConnectionUtil.getProperties();
    props.setProperty(PGProperty.CACHE_READ_ONLY_STATE.getName(), enableCache);
    props.setProperty(PGProperty.CACHE_ISOLATION_STATE.getName(), enableCache);

    connection = DriverManager.getConnection(ConnectionUtil.getURL(), props);
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    connection.close();
  }

  @Benchmark
  public void testReadOnly(Blackhole b) throws SQLException {
    b.consume(connection.isReadOnly());
  }

  @Benchmark
  public void testTransactionIsolation(Blackhole b) throws SQLException {
    b.consume(connection.getTransactionIsolation());
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(TransactionModeCache.class.getSimpleName())
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }

}
