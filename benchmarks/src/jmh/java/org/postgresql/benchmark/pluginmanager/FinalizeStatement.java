/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.pluginmanager;

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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Here we measure the time it takes to create and close a dummy statement.
 *
 * <p>To run this and other benchmarks (you can run the class from within IDE):
 *
 * <blockquote> <code>mvn package &amp;&amp; java -classpath postgresql-driver
 * .jar:target/benchmarks.jar
 * -Duser=postgres -Dpassword=postgres -Dport=5433  -wi 10 -i 10 -f 1</code> </blockquote>
 *
 * <p>To run with profiling:
 *
 * <blockquote> <code>java -classpath postgresql-driver.jar:target/benchmarks.jar -prof gc -f 1 -wi
 * 10 -i 10</code> </blockquote>
 */
@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class FinalizeStatement {
  @Param({"0", "1", "10", "100"})
  private int leakPct;

  private float leakPctFloat;

  private Connection connectionNoPm;
  private Connection connectionPm0;
  private Connection connectionPm1;
  private Connection connectionPm10;

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(org.postgresql.benchmark.statement.FinalizeStatement.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "false");
    connectionNoPm = DriverManager.getConnection(ConnectionUtil.getURL(), props);

    props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "true");
    props.setProperty("connectionPluginFactories", ""); // explicitly sets no plugin
    connectionPm0 = DriverManager.getConnection(ConnectionUtil.getURL(), props);

    props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "true");
    props.setProperty("connectionPluginFactories",
        "org.postgresql.plugins.demo.DummyConnectionPluginFactory");
    connectionPm1 = DriverManager.getConnection(ConnectionUtil.getURL(), props);

    props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "true");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("org.postgresql.plugins.demo.DummyConnectionPluginFactory");
    }
    props.setProperty("connectionPluginFactories", sb.toString());
    connectionPm10 = DriverManager.getConnection(ConnectionUtil.getURL(), props);

    leakPctFloat = 0.01f * leakPct;
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    connectionNoPm.close();
    connectionPm0.close();
    connectionPm1.close();
    connectionPm10.close();
  }

  @Benchmark
  public Statement createAndLeak_disabled() throws SQLException {
    Statement statement = connectionNoPm.createStatement();
    Random rnd;
    rnd = java.util.concurrent.ThreadLocalRandom.current();
    if (rnd.nextFloat() >= leakPctFloat) {
      statement.close();
    }
    return statement;
  }

  @Benchmark
  public Statement createAndLeak_0() throws SQLException {
    Statement statement = connectionPm0.createStatement();
    Random rnd;
    rnd = java.util.concurrent.ThreadLocalRandom.current();
    if (rnd.nextFloat() >= leakPctFloat) {
      statement.close();
    }
    return statement;
  }

  @Benchmark
  public Statement createAndLeak_1() throws SQLException {
    Statement statement = connectionPm1.createStatement();
    Random rnd;
    rnd = java.util.concurrent.ThreadLocalRandom.current();
    if (rnd.nextFloat() >= leakPctFloat) {
      statement.close();
    }
    return statement;
  }

  @Benchmark
  public Statement createAndLeak_10() throws SQLException {
    Statement statement = connectionPm10.createStatement();
    Random rnd;
    rnd = java.util.concurrent.ThreadLocalRandom.current();
    if (rnd.nextFloat() >= leakPctFloat) {
      statement.close();
    }
    return statement;
  }
}
