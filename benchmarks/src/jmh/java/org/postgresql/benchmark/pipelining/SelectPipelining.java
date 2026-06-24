/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.benchmark.pipelining;

import org.postgresql.benchmark.profilers.FlightRecorderProfiler;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Fork(value = 5, jvmArgsPrepend = "-Xmx128m")
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class SelectPipelining {
  private static final int ROWS = 10;
  private static final String FETCH_BY_PARENT = "select id, name, parent_id from pipelining_perf_test2 where parent_id=?";
  private Connection connection;
  private PreparedStatement ps1;
  private ExecutorService executorService;

  @Param({"true", "false"})
  boolean pipelining;

  @Setup(Level.Trial)
  public void setUp(BenchmarkParams bp)
      throws SQLException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    Properties properties = new Properties();
    properties.setProperty("pipelineMode", Boolean.toString(pipelining));
    properties.setProperty("sslmode", "disable");
    connection = TestUtil.openDB(properties);

    try (Statement s = connection.createStatement()) {
      s.execute("drop table if exists pipelining_perf_test1");
      s.execute("drop table if exists pipelining_perf_test2");
      s.execute("create table pipelining_perf_test1(id int primary key, name varchar(100))");
      s.execute("create table pipelining_perf_test2(id int primary key, name varchar(100), parent_id int)");
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "insert into pipelining_perf_test1(id, name) values(?, ?)")) {
      for (int i = 1; i <= ROWS; i++) {
        insert.setInt(1, i);
        insert.setString(2, "Test " + i);
        insert.execute();
      }
    }
    try (PreparedStatement insert = connection.prepareStatement(
        "insert into pipelining_perf_test2(id, name, parent_id) values(?, ?, ?)")) {
      for (int i = 1; i <= ROWS * 10; i++) {
        insert.setInt(1, i);
        insert.setString(2, "Test " + i);
        insert.setInt(3, (i / 10) + (i % 10 == 0 ? 0 : 1));
        insert.execute();
      }
    }
    ps1 = connection.prepareStatement("select id, name from pipelining_perf_test1");
    // Put it into the cache
    connection.prepareStatement(FETCH_BY_PARENT);
    executorService = pipelining ? (ExecutorService) Executors.class.getMethod("newVirtualThreadPerTaskExecutor").invoke(null)
        : null;
  }

  @Benchmark
  public int select() throws SQLException, ExecutionException, InterruptedException {
    if (executorService != null) {
      ArrayList<Future<Integer>> futures = new ArrayList<>(ROWS * 10);
      int resultCount = selectWithChildren(futures);
      for (Future<Integer> future : futures) {
        resultCount += future.get();
      }
      return resultCount;
    } else {
      return selectWithChildren(null);
    }
  }

  public int selectWithChildren(List<Future<Integer>> futures) throws SQLException {
    int resultCount = 0;
    try (ResultSet resultSet = ps1.executeQuery()) {
      while (resultSet.next()) {
        int id = resultSet.getInt(1);
        String name = resultSet.getString(2);
        if (id > 0 && name != null) {
          resultCount++;
          if (executorService != null) {
            futures.add(executorService.submit(() -> fetch(id)));
          } else {
            resultCount += fetch(id);
          }
          Blackhole.consumeCPU(500_000);
        }
      }
    }
    return resultCount;
  }

  private int fetch(int parentId) throws SQLException {
    int resultCount = 0;
    try (PreparedStatement ps2 = connection.prepareStatement(FETCH_BY_PARENT)) {
      ps2.setInt(1, parentId);
      try (ResultSet resultSet = ps2.executeQuery()) {
        while (resultSet.next()) {
          int id = resultSet.getInt(1);
          String name = resultSet.getString(2);
          if (id > 0 && name != null) {
            resultCount++;
          }
        }
      }
    }
    return resultCount;
  }

  public static void main(String[] args) throws RunnerException {
    //DriverManager.setLogWriter(new PrintWriter(System.out));
    //Driver.setLogLevel(2);
    Options opt = new OptionsBuilder()
        .include(SelectPipelining.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .addProfiler(FlightRecorderProfiler.class)
        .detectJvmArgs()
        .build();

    new Runner(opt).run();
  }

}
