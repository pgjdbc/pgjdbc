/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.pluginmanager;

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

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class BindArray {
  Integer[] ints;
  int arraySize = 1000;
  private Connection connectionNoPm;
  private Connection connectionPm0;
  private Connection connectionPm1;
  private Connection connectionPm10;
  private PreparedStatement psNoPm;
  private PreparedStatement psPm0;
  private PreparedStatement psPm1;
  private PreparedStatement psPm10;

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(org.postgresql.benchmark.statement.BindArray.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .addProfiler(FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }

  @Setup(Level.Trial)
  public void setUp() throws SQLException {
    Properties props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "false");
    connectionNoPm = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    psNoPm = connectionNoPm.prepareStatement("SELECT ?");
    ints = new Integer[arraySize];
    for (int i = 0; i < arraySize; i++) {
      ints[i] = i + 1;
    }

    props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "true");
    props.setProperty("connectionPluginFactories", ""); // explicitly sets no plugin
    connectionPm0 = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    psPm0 = connectionPm0.prepareStatement("SELECT ?");
    ints = new Integer[arraySize];
    for (int i = 0; i < arraySize; i++) {
      ints[i] = i + 1;
    }

    props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "true");
    props.setProperty("connectionPluginFactories",
        "org.postgresql.pluginManager.simple.DummyConnectionPluginFactory");
    connectionPm1 = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    psPm1 = connectionPm1.prepareStatement("SELECT ?");
    ints = new Integer[arraySize];
    for (int i = 0; i < arraySize; i++) {
      ints[i] = i + 1;
    }

    props = ConnectionUtil.getProperties();
    props.setProperty("useConnectionPlugins", "true");
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      if (i > 0) {
        sb.append(",");
      }
      sb.append("org.postgresql.pluginManager.simple.DummyConnectionPluginFactory");
    }
    props.setProperty("connectionPluginFactories", sb.toString());
    connectionPm10 = DriverManager.getConnection(ConnectionUtil.getURL(), props);
    psPm10 = connectionPm10.prepareStatement("SELECT ?");
    ints = new Integer[arraySize];
    for (int i = 0; i < arraySize; i++) {
      ints[i] = i + 1;
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() throws SQLException {
    psNoPm.close();
    connectionNoPm.close();

    psPm0.close();
    connectionPm0.close();

    psPm1.close();
    connectionPm1.close();

    psPm10.close();
    connectionPm10.close();
  }

  @Benchmark
  public Statement setObject_disabled() throws SQLException {
    Array sqlInts = connectionNoPm.createArrayOf("int", ints);
    psNoPm.setObject(1, sqlInts, Types.ARRAY);
    return psNoPm;
  }

  @Benchmark
  public Statement setObject_0() throws SQLException {
    Array sqlInts = connectionPm0.createArrayOf("int", ints);
    psPm0.setObject(1, sqlInts, Types.ARRAY);
    return psPm0;
  }

  @Benchmark
  public Statement setObject_1() throws SQLException {
    Array sqlInts = connectionPm1.createArrayOf("int", ints);
    psPm1.setObject(1, sqlInts, Types.ARRAY);
    return psPm1;
  }

  @Benchmark
  public Statement setObject_10() throws SQLException {
    Array sqlInts = connectionPm10.createArrayOf("int", ints);
    psPm10.setObject(1, sqlInts, Types.ARRAY);
    return psPm10;
  }

  @Benchmark
  public Statement setArray_disabled() throws SQLException {
    Array sqlInts = connectionNoPm.createArrayOf("int", ints);
    psNoPm.setArray(1, sqlInts);
    return psNoPm;
  }

  @Benchmark
  public Statement setArray_0() throws SQLException {
    Array sqlInts = connectionPm0.createArrayOf("int", ints);
    psPm0.setArray(1, sqlInts);
    return psPm0;
  }

  @Benchmark
  public Statement setArray_1() throws SQLException {
    Array sqlInts = connectionPm1.createArrayOf("int", ints);
    psPm1.setArray(1, sqlInts);
    return psPm1;
  }

  @Benchmark
  public Statement setArray_10() throws SQLException {
    Array sqlInts = connectionPm10.createArrayOf("int", ints);
    psPm10.setArray(1, sqlInts);
    return psPm10;
  }
}
